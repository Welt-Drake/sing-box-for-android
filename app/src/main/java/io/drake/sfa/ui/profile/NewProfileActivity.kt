package io.drake.sfa.ui.profile

import android.net.Uri
import android.os.Bundle
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import io.drake.libbox.Libbox
import io.drake.sfa.R
import io.drake.sfa.constant.EnabledType
import io.drake.sfa.database.Profile
import io.drake.sfa.database.ProfileManager
import io.drake.sfa.database.TypedProfile
import io.drake.sfa.databinding.ActivityAddProfileBinding
import io.drake.sfa.ktx.addTextChangedListener
import io.drake.sfa.ktx.errorDialogBuilder
import io.drake.sfa.ktx.removeErrorIfNotEmpty
import io.drake.sfa.ktx.showErrorIfEmpty
import io.drake.sfa.ktx.startFilesForResult
import io.drake.sfa.ktx.text
import io.drake.sfa.ui.shared.AbstractActivity
import io.drake.sfa.utils.HTTPClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.util.Date

class NewProfileActivity : AbstractActivity() {
    enum class FileSource(val formatted: String) {
        CreateNew("Create New"),
        Import("Import");
    }

    private var binding: ActivityAddProfileBinding? = null
    private val importFile =
        registerForActivityResult(ActivityResultContracts.GetContent()) { fileURI ->
            val binding = binding ?: return@registerForActivityResult
            if (fileURI != null) {
                binding.sourceURL.editText?.setText(fileURI.toString())
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setTitle(R.string.title_new_profile)
        val binding = ActivityAddProfileBinding.inflate(layoutInflater)
        this.binding = binding
        setContentView(binding.root)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        intent.getStringExtra("importName")?.also { importName ->
            intent.getStringExtra("importURL")?.also { importURL ->
                binding.name.editText?.setText(importName)
                binding.type.text = TypedProfile.Type.Remote.name
                binding.remoteURL.editText?.setText(importURL)
            }
        }

        binding.name.removeErrorIfNotEmpty()
        binding.type.addTextChangedListener {
            when (it) {
                TypedProfile.Type.Local.name -> {
                    binding.localFields.isVisible = true
                    binding.remoteFields.isVisible = false
                }

                TypedProfile.Type.Remote.name -> {
                    binding.localFields.isVisible = false
                    binding.remoteFields.isVisible = true
                    if (binding.autoUpdateInterval.text.toIntOrNull() == null) {
                        binding.autoUpdateInterval.text = "60"
                    }
                }
            }
        }
        binding.fileSourceMenu.addTextChangedListener {
            when (it) {
                FileSource.CreateNew.formatted -> {
                    binding.importFileButton.isVisible = false
                    binding.sourceURL.isVisible = false
                }

                FileSource.Import.formatted -> {
                    binding.importFileButton.isVisible = true
                    binding.sourceURL.isVisible = true
                }
            }
        }
        binding.importFileButton.setOnClickListener {
            startFilesForResult(importFile, "application/json")
        }
        binding.createProfile.setOnClickListener(this::createProfile)
        binding.autoUpdateInterval.addTextChangedListener(this::updateAutoUpdateInterval)
    }

    override fun onDestroy() {
        super.onDestroy()
        binding = null
    }

    private fun createProfile(view: View) {
        val binding = binding ?: return
        if (binding.name.showErrorIfEmpty()) {
            return
        }
        when (binding.type.text) {
            TypedProfile.Type.Local.name -> {
                when (binding.fileSourceMenu.text) {
                    FileSource.Import.formatted -> {
                        if (binding.sourceURL.showErrorIfEmpty()) {
                            return
                        }
                    }
                }
            }

            TypedProfile.Type.Remote.name -> {
                if (binding.remoteURL.showErrorIfEmpty()) {
                    return
                }
            }
        }
        binding.progressView.isVisible = true
        lifecycleScope.launch(Dispatchers.IO) {
            runCatching {
                createProfile0()
            }.onFailure { e ->
                withContext(Dispatchers.Main) {
                    binding.progressView.isVisible = false
                    errorDialogBuilder(e).show()
                }
            }
        }
    }

    private suspend fun createProfile0() {
        val binding = binding ?: return
        val typedProfile = TypedProfile()
        val profile = Profile(name = binding.name.text, typed = typedProfile)
        profile.userOrder = ProfileManager.nextOrder()
        val fileID = ProfileManager.nextFileID()
        val configDirectory = File(filesDir, "configs").also { it.mkdirs() }
        val configFile = File(configDirectory, "$fileID.json")
        typedProfile.path = configFile.path

        when (binding.type.text) {
            TypedProfile.Type.Local.name -> {
                typedProfile.type = TypedProfile.Type.Local

                when (binding.fileSourceMenu.text) {
                    FileSource.CreateNew.formatted -> {
                        configFile.writeText("{}")
                    }

                    FileSource.Import.formatted -> {
                        val sourceURL = binding.sourceURL.text
                        val content = if (sourceURL.startsWith("content://")) {
                            val inputStream =
                                contentResolver.openInputStream(Uri.parse(sourceURL)) as InputStream
                            inputStream.use { it.bufferedReader().readText() }
                        } else if (sourceURL.startsWith("file://")) {
                            File(sourceURL).readText()
                        } else if (sourceURL.startsWith("http://") || sourceURL.startsWith("https://")) {
                            HTTPClient().use { it.getString(sourceURL) }
                        } else {
                            error("unsupported source: $sourceURL")
                        }
                        Libbox.checkConfig(content)
                        configFile.writeText(content)
                    }
                }
            }

            TypedProfile.Type.Remote.name -> {
                typedProfile.type = TypedProfile.Type.Remote
                val remoteURL = binding.remoteURL.text
                val content = HTTPClient().use { it.getString(remoteURL) }
                Libbox.checkConfig(content)
                configFile.writeText(content)
                typedProfile.remoteURL = remoteURL
                typedProfile.lastUpdated = Date()
                typedProfile.autoUpdate = EnabledType.valueOf(binding.autoUpdate.text).boolValue
                binding.autoUpdateInterval.text.toIntOrNull()?.also {
                    typedProfile.autoUpdateInterval = it
                }
            }
        }
        ProfileManager.create(profile)
        withContext(Dispatchers.Main) {
            binding.progressView.isVisible = false
            finish()
        }
    }

    private fun updateAutoUpdateInterval(newValue: String) {
        val binding = binding ?: return
        if (newValue.isBlank()) {
            binding.autoUpdateInterval.error = getString(R.string.profile_input_required)
            return
        }
        val intValue = try {
            newValue.toInt()
        } catch (e: Exception) {
            binding.autoUpdateInterval.error = e.localizedMessage
            return
        }
        if (intValue < 15) {
            binding.autoUpdateInterval.error =
                getString(R.string.profile_auto_update_interval_minimum_hint)
            return
        }
        binding.autoUpdateInterval.error = null
    }


}