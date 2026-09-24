package com.mdevz.sp.ui.profiles

import android.content.Context
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import com.mdevz.sp.core.config.ProfileUriCodec
import java.io.File
import android.widget.PopupMenu
import androidx.core.os.bundleOf
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.mdevz.sp.R
import com.mdevz.sp.core.model.SshProfile
import com.mdevz.sp.databinding.FragmentProfilesBinding
import com.mdevz.sp.databinding.ItemProfileGridBinding
import com.mdevz.sp.databinding.ItemProfileListBinding
import com.mdevz.sp.service.TunnelRuntime
import java.util.UUID

class ProfilesFragment : Fragment() {


    private val importVpDocuments =
        registerForActivityResult(
            ActivityResultContracts.OpenMultipleDocuments()
        ) {
            uris ->
            if (uris.isNotEmpty()) {
                importVpFiles(uris)
            }
        }

    private enum class ProfileFilter {
        ALL,
        FAVORITE,
        PINNED,
        ACTIVE
    }

    private var profileFilter =
        ProfileFilter.ALL

    private var _binding:
        FragmentProfilesBinding? = null

    private val binding:
        FragmentProfilesBinding
        get() = requireNotNull(_binding)

    private lateinit var runtime:
        TunnelRuntime

    private lateinit var adapter:
        ProfileAdapter

    private var allProfiles:
        List<ProfileEntry> = emptyList()

    private var query:
        String = ""

    private var gridMode:
        Boolean = false

    private var favoriteProfileIds:
        Set<String> = emptySet()

    private var pinnedProfileIds:
        Set<String> = emptySet()

    private var activeProfileId:
        String? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding =
            FragmentProfilesBinding.inflate(
                inflater,
                container,
                false
            )

        return binding.root
    }

    override fun onViewCreated(
        view: View,
        savedInstanceState: Bundle?
    ) {
        super.onViewCreated(
            view,
            savedInstanceState
        )

        runtime =
            TunnelRuntime.get(
                requireContext()
            )

        val preferences =
            requireContext()
                .getSharedPreferences(
                    PROFILE_UI_PREFERENCES,
                    Context.MODE_PRIVATE
                )

        gridMode =
            preferences.getBoolean(
                KEY_GRID_MODE,
                false
            )

        favoriteProfileIds =
            preferences.getStringSet(
                KEY_FAVORITE_PROFILE_IDS,
                emptySet()
            )?.toSet().orEmpty()

        pinnedProfileIds =
            preferences.getStringSet(
                KEY_PINNED_PROFILE_IDS,
                emptySet()
            )?.toSet().orEmpty()

        activeProfileId =
            preferences.getString(
                KEY_ACTIVE_PROFILE,
                null
            )

        adapter =
            ProfileAdapter(
                onOpen = ::openEditor,
                onMenu = ::showProfileMenu
            )

        binding.listProfiles.adapter =
            adapter

        applyLayoutManager()

        binding.buttonViewMode
            .setOnClickListener {
                gridMode =
                    !gridMode

                preferences.edit()
                    .putBoolean(
                        KEY_GRID_MODE,
                        gridMode
                    )
                    .apply()

                applyLayoutManager()
                render()
            }

        binding.buttonImportProfiles
            .setOnClickListener {
                importVpDocuments.launch(
                    arrayOf(
                        "application/octet-stream",
                        "text/plain"
                    )
                )
            }

        binding.buttonProfileFilter
            .setOnClickListener {
                showFilterMenu()
            }

        binding.buttonAddProfile
            .setOnClickListener {
                createProfile()
            }

        binding.buttonCreateProfile
            .setOnClickListener {
                createProfile()
            }

        binding.inputProfileSearch
            .addTextChangedListener(
                object : TextWatcher {
                    override fun beforeTextChanged(
                        s: CharSequence?,
                        start: Int,
                        count: Int,
                        after: Int
                    ) = Unit

                    override fun onTextChanged(
                        s: CharSequence?,
                        start: Int,
                        before: Int,
                        count: Int
                    ) {
                        query =
                            s?.toString()
                                ?.trim()
                                .orEmpty()

                        render()
                    }

                    override fun afterTextChanged(
                        s: Editable?
                    ) = Unit
                }
            )
    }

    override fun onResume() {
        super.onResume()

        if (_binding != null) {
            reloadProfiles()
        }
    }

    override fun onDestroyView() {
        if (::adapter.isInitialized) {
            binding.listProfiles.adapter =
                null
        }

        _binding = null
        super.onDestroyView()
    }

    private fun reloadProfiles() {
        allProfiles =
            runtime.profiles
                .listIds()
                .mapNotNull {
                    id ->
                    runtime.profiles
                        .load(id)
                        ?.let {
                            profile ->
                            ProfileEntry(
                                id = id,
                                profile = profile
                            )
                        }
                }
                .sortedWith(
                    compareByDescending<ProfileEntry> {
                        it.id ==
                            activeProfileId
                    }.thenByDescending {
                        pinnedProfileIds.contains(
                            it.id
                        )
                    }.thenByDescending {
                        favoriteProfileIds.contains(
                            it.id
                        )
                    }.thenBy {
                        it.profile.name
                            .lowercase()
                    }.thenBy {
                        it.id
                    }
                )

        if (
            activeProfileId != null &&
            allProfiles.none {
                it.id ==
                    activeProfileId
            }
        ) {
            activeProfileId = null

            requireContext()
                .getSharedPreferences(
                    PROFILE_UI_PREFERENCES,
                    Context.MODE_PRIVATE
                )
                .edit()
                .remove(
                    KEY_ACTIVE_PROFILE
                )
                .apply()
        }

        render()
    }


    private fun showFilterMenu() {
        val popup =
            PopupMenu(
                requireContext(),
                binding.buttonProfileFilter
            )

        popup.menu.add(
            R.string.profiles_filter_all
        ).setOnMenuItemClickListener {
            setProfileFilter(
                ProfileFilter.ALL
            )
            true
        }

        popup.menu.add(
            R.string.profiles_filter_favorite
        ).setOnMenuItemClickListener {
            setProfileFilter(
                ProfileFilter.FAVORITE
            )
            true
        }

        popup.menu.add(
            R.string.profiles_filter_pinned
        ).setOnMenuItemClickListener {
            setProfileFilter(
                ProfileFilter.PINNED
            )
            true
        }

        popup.menu.add(
            R.string.profiles_filter_active
        ).setOnMenuItemClickListener {
            setProfileFilter(
                ProfileFilter.ACTIVE
            )
            true
        }

        popup.show()
    }

    private fun setProfileFilter(
        filter: ProfileFilter
    ) {
        profileFilter = filter

        binding.buttonProfileFilter.setText(
            when (filter) {
                ProfileFilter.ALL ->
                    R.string.profiles_filter_all

                ProfileFilter.FAVORITE ->
                    R.string.profiles_filter_favorite

                ProfileFilter.PINNED ->
                    R.string.profiles_filter_pinned

                ProfileFilter.ACTIVE ->
                    R.string.profiles_filter_active
            }
        )

        render()
    }

    private fun matchesProfileFilter(
        entry: ProfileEntry
    ): Boolean =
        when (profileFilter) {
            ProfileFilter.ALL ->
                true

            ProfileFilter.FAVORITE ->
                favoriteProfileIds.contains(
                    entry.id
                )

            ProfileFilter.PINNED ->
                pinnedProfileIds.contains(
                    entry.id
                )

            ProfileFilter.ACTIVE ->
                entry.id == activeProfileId
        }

    private fun render() {
        val normalizedQuery =
            query.lowercase()

        val searchVisible =
            if (normalizedQuery.isBlank()) {
                allProfiles
            } else {
                allProfiles.filter {
                    entry ->
                    entry.profile.name
                        .lowercase()
                        .contains(
                            normalizedQuery
                        ) ||
                        entry.profile.sshHost
                            .lowercase()
                            .contains(
                                normalizedQuery
                            )
                }
            }

        val visible =
            searchVisible.filter {
                entry ->
                matchesProfileFilter(
                    entry
                )
            }

        adapter.submit(
            entries = visible,
            gridMode = gridMode,
            activeProfileId =
                activeProfileId
        )

        binding.profileEmptyState.isVisible =
            allProfiles.isEmpty()

        binding.listProfiles.isVisible =
            allProfiles.isNotEmpty()

        binding.buttonViewMode.text =
            getString(
                if (gridMode) {
                    R.string.profiles_list_view
                } else {
                    R.string.profiles_grid_view
                }
            )
    }

    private fun applyLayoutManager() {
        binding.listProfiles.layoutManager =
            if (gridMode) {
                GridLayoutManager(
                    requireContext(),
                    GRID_SPAN_COUNT
                )
            } else {
                LinearLayoutManager(
                    requireContext()
                )
            }
    }

    private fun createProfile() {
        val id =
            UUID.randomUUID()
                .toString()

        runtime.profiles.save(
            id,
            SshProfile(
                name = "",
                sshHost = "",
                username = "",
                password = ""
            )
        )

        openEditor(id)
    }

    private fun openEditor(
        id: String
    ) {
        findNavController()
            .navigate(
                R.id.action_profiles_to_editor,
                bundleOf(
                    "profile_id" to id
                )
            )
    }

    private fun showProfileMenu(
        anchor: View,
        entry: ProfileEntry
    ) {
        val popup =
            PopupMenu(
                requireContext(),
                anchor
            )

        popup.menu.add(
            R.string.action_edit
        ).setOnMenuItemClickListener {
            openEditor(entry.id)
            true
        }

        popup.menu.add(
            R.string.profile_set_active
        ).setOnMenuItemClickListener {
            setActive(entry.id)
            true
        }

        popup.menu.add(
            if (
                favoriteProfileIds.contains(
                    entry.id
                )
            ) {
                R.string.profile_remove_favorite
            } else {
                R.string.profile_add_favorite
            }
        ).setOnMenuItemClickListener {
            toggleFavorite(entry.id)
            true
        }

        popup.menu.add(
            if (
                pinnedProfileIds.contains(
                    entry.id
                )
            ) {
                R.string.profile_unpin
            } else {
                R.string.profile_pin
            }
        ).setOnMenuItemClickListener {
            togglePinned(entry.id)
            true
        }

        popup.menu.add(
            R.string.profile_duplicate
        ).setOnMenuItemClickListener {
            duplicate(entry)
            true
        }

        popup.menu.add(
            R.string.profile_export
        ).setOnMenuItemClickListener {
            exportProfileVp(entry)
            true
        }

        popup.menu.add(
            R.string.profile_delete
        ).setOnMenuItemClickListener {
            confirmDelete(entry)
            true
        }

        popup.show()
    }

    private fun toggleFavorite(
        id: String
    ) {
        favoriteProfileIds =
            if (favoriteProfileIds.contains(id)) {
                favoriteProfileIds - id
            } else {
                favoriteProfileIds + id
            }

        requireContext()
            .getSharedPreferences(
                PROFILE_UI_PREFERENCES,
                Context.MODE_PRIVATE
            )
            .edit()
            .putStringSet(
                KEY_FAVORITE_PROFILE_IDS,
                favoriteProfileIds
            )
            .apply()

        reloadProfiles()
    }

    private fun togglePinned(
        id: String
    ) {
        pinnedProfileIds =
            if (pinnedProfileIds.contains(id)) {
                pinnedProfileIds - id
            } else {
                pinnedProfileIds + id
            }

        requireContext()
            .getSharedPreferences(
                PROFILE_UI_PREFERENCES,
                Context.MODE_PRIVATE
            )
            .edit()
            .putStringSet(
                KEY_PINNED_PROFILE_IDS,
                pinnedProfileIds
            )
            .apply()

        reloadProfiles()
    }

    private fun setActive(
        id: String
    ) {
        activeProfileId =
            id

        requireContext()
            .getSharedPreferences(
                PROFILE_UI_PREFERENCES,
                Context.MODE_PRIVATE
            )
            .edit()
            .putString(
                KEY_ACTIVE_PROFILE,
                id
            )
            .apply()

        reloadProfiles()
    }


    private fun importVpFiles(
        uris: List<Uri>
    ) {
        var imported = 0
        var failed = 0

        uris.forEach {
            uri ->
            val result =
                runCatching {
                    val encoded =
                        requireContext()
                            .contentResolver
                            .openInputStream(uri)
                            ?.bufferedReader(
                                Charsets.UTF_8
                            )
                            ?.use {
                                reader ->
                                reader.readText()
                            }
                            ?: throw IllegalArgumentException()

                    require(
                        ProfileUriCodec.isProfileUri(
                            encoded
                        )
                    )

                    val profile =
                        ProfileUriCodec.decode(
                            encoded
                        )

                    runtime.profiles.save(
                        UUID.randomUUID()
                            .toString(),
                        profile
                    )
                }

            if (result.isSuccess) {
                imported += 1
            } else {
                failed += 1
            }
        }

        reloadProfiles()

        val message =
            if (failed == 0) {
                resources.getQuantityString(
                    R.plurals.profiles_imported_count,
                    imported,
                    imported
                )
            } else {
                getString(
                    R.string.profiles_import_result,
                    imported,
                    failed
                )
            }

        Toast.makeText(
            requireContext(),
            message,
            Toast.LENGTH_LONG
        ).show()
    }

    private fun exportProfileVp(
        entry: ProfileEntry
    ) {
        val result =
            runCatching {
                val encoded =
                    ProfileUriCodec.encode(
                        entry.profile
                    )

                val directory =
                    File(
                        requireContext().cacheDir,
                        "profile_exports"
                    )

                if (
                    !directory.exists() &&
                    !directory.mkdirs()
                ) {
                    throw IllegalStateException()
                }

                val baseName =
                    entry.profile.name
                        .trim()
                        .ifBlank {
                            getString(
                                R.string.profile_fallback_name
                            )
                        }
                        .replace(
                            Regex(
                                "[^A-Za-z0-9._-]+"
                            ),
                            "_"
                        )
                        .trim('_')
                        .ifBlank {
                            "profile"
                        }
                        .take(80)

                val file =
                    File(
                        directory,
                        "$baseName.vp"
                    )

                file.writeText(
                    encoded,
                    Charsets.UTF_8
                )

                val uri =
                    FileProvider.getUriForFile(
                        requireContext(),
                        "${requireContext().packageName}.fileprovider",
                        file
                    )

                val shareIntent =
                    Intent(
                        Intent.ACTION_SEND
                    ).apply {
                        type =
                            "application/octet-stream"

                        putExtra(
                            Intent.EXTRA_STREAM,
                            uri
                        )

                        addFlags(
                            Intent.FLAG_GRANT_READ_URI_PERMISSION
                        )
                    }

                startActivity(
                    Intent.createChooser(
                        shareIntent,
                        getString(
                            R.string.profile_export
                        )
                    )
                )
            }

        if (result.isFailure) {
            Toast.makeText(
                requireContext(),
                R.string.profile_export_failed,
                Toast.LENGTH_LONG
            ).show()
        }
    }
    private fun duplicate(
        entry: ProfileEntry
    ) {
        val newId =
            UUID.randomUUID()
                .toString()

        val source =
            entry.profile

        runtime.profiles.save(
            newId,
            source.copy(
                name =
                    getString(
                        R.string.profile_copy_name,
                        source.name
                            .ifBlank {
                                getString(
                                    R.string.profile_fallback_name
                                )
                            }
                    )
            )
        )

        reloadProfiles()
    }

    private fun confirmDelete(
        entry: ProfileEntry
    ) {
        MaterialAlertDialogBuilder(
            requireContext()
        )
            .setTitle(
                R.string.delete_profile_title
            )
            .setMessage(
                getString(
                    R.string.profile_delete_confirm,
                    entry.profile.name
                        .ifBlank {
                            getString(
                                R.string.profile_fallback_name
                            )
                        }
                )
            )
            .setNegativeButton(
                R.string.action_cancel,
                null
            )
            .setPositiveButton(
                R.string.profile_delete
            ) {
                _,
                _ ->
                deleteProfile(entry.id)
            }
            .show()
    }

    private fun deleteProfile(
        id: String
    ) {
        runtime.profiles.delete(id)

        if (activeProfileId == id) {
            activeProfileId = null

            requireContext()
                .getSharedPreferences(
                    PROFILE_UI_PREFERENCES,
                    Context.MODE_PRIVATE
                )
                .edit()
                .remove(
                    KEY_ACTIVE_PROFILE
                )
                .apply()
        }

        reloadProfiles()
    }

    private data class ProfileEntry(
        val id: String,
        val profile: SshProfile
    )

    private class ProfileAdapter(
        private val onOpen:
            (String) -> Unit,
        private val onMenu:
            (View, ProfileEntry) -> Unit
    ) : RecyclerView.Adapter<
        RecyclerView.ViewHolder
    >() {

        private val entries =
            mutableListOf<ProfileEntry>()

        private var gridMode =
            false

        private var activeProfileId:
            String? = null

        fun submit(
            entries: List<ProfileEntry>,
            gridMode: Boolean,
            activeProfileId: String?
        ) {
            this.entries.clear()
            this.entries.addAll(entries)
            this.gridMode = gridMode
            this.activeProfileId =
                activeProfileId

            notifyDataSetChanged()
        }

        override fun getItemViewType(
            position: Int
        ): Int =
            if (gridMode) {
                VIEW_GRID
            } else {
                VIEW_LIST
            }

        override fun onCreateViewHolder(
            parent: ViewGroup,
            viewType: Int
        ): RecyclerView.ViewHolder =
            if (viewType == VIEW_GRID) {
                GridHolder(
                    ItemProfileGridBinding.inflate(
                        LayoutInflater.from(
                            parent.context
                        ),
                        parent,
                        false
                    )
                )
            } else {
                ListHolder(
                    ItemProfileListBinding.inflate(
                        LayoutInflater.from(
                            parent.context
                        ),
                        parent,
                        false
                    )
                )
            }

        override fun onBindViewHolder(
            holder: RecyclerView.ViewHolder,
            position: Int
        ) {
            val entry =
                entries[position]

            when (holder) {
                is ListHolder ->
                    holder.bind(
                        entry,
                        entry.id ==
                            activeProfileId
                    )

                is GridHolder ->
                    holder.bind(
                        entry,
                        entry.id ==
                            activeProfileId
                    )
            }
        }

        override fun getItemCount(): Int =
            entries.size

        private inner class ListHolder(
            private val binding:
                ItemProfileListBinding
        ) : RecyclerView.ViewHolder(
            binding.root
        ) {

            fun bind(
                entry: ProfileEntry,
                active: Boolean
            ) {
                binding.textProfileName.text =
                    entry.profile.name
                        .ifBlank {
                            itemView.context
                                .getString(
                                    R.string.profile_fallback_name
                                )
                        }

                binding.textProfileHost.text =
                    entry.profile.sshHost
                        .ifBlank {
                            itemView.context
                                .getString(
                                    R.string.ssh_host_not_set
                                )
                        }

                binding.textProfileActive
                    .isVisible =
                    active

                binding.root
                    .setOnClickListener {
                        onOpen(entry.id)
                    }

                binding.buttonProfileMenu
                    .setOnClickListener {
                        onMenu(it, entry)
                    }
            }
        }

        private inner class GridHolder(
            private val binding:
                ItemProfileGridBinding
        ) : RecyclerView.ViewHolder(
            binding.root
        ) {

            fun bind(
                entry: ProfileEntry,
                active: Boolean
            ) {
                binding.textProfileName.text =
                    entry.profile.name
                        .ifBlank {
                            itemView.context
                                .getString(
                                    R.string.profile_fallback_name
                                )
                        }

                binding.textProfileHost.text =
                    entry.profile.sshHost
                        .ifBlank {
                            itemView.context
                                .getString(
                                    R.string.ssh_host_not_set
                                )
                        }

                binding.textProfileActive
                    .isVisible =
                    active

                binding.root
                    .setOnClickListener {
                        onOpen(entry.id)
                    }

                binding.buttonProfileMenu
                    .setOnClickListener {
                        onMenu(it, entry)
                    }
            }
        }

        private companion object {
            const val VIEW_LIST =
                1

            const val VIEW_GRID =
                2
        }
    }

    private companion object {
        const val PROFILE_UI_PREFERENCES =
            "profile_ui_preferences"

        const val KEY_GRID_MODE =
            "grid_mode"

        const val KEY_FAVORITE_PROFILE_IDS =
            "favorite_profile_ids"

        const val KEY_PINNED_PROFILE_IDS =
            "pinned_profile_ids"

        const val KEY_ACTIVE_PROFILE =
            "active_profile_id"

        const val GRID_SPAN_COUNT =
            2
    }
}
