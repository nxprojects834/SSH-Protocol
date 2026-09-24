package com.mdevz.sp.ui.logs

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import com.mdevz.sp.R
import com.mdevz.sp.databinding.FragmentLogsBinding
import com.mdevz.sp.databinding.ItemLogEventBinding
import com.mdevz.sp.tunnel.MemoryTunnelLogger
import com.mdevz.sp.tunnel.TunnelLogEntry
import com.mdevz.sp.tunnel.TunnelLogLevel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class LogsController(
    private val context: Context,
    private val binding: FragmentLogsBinding,
    private val logger: MemoryTunnelLogger
) {

    private val adapter = LogAdapter()

    fun setup() {
        binding.listLogs.adapter = adapter

        binding.buttonClearLogs.setOnClickListener {
            logger.clear()
        }

        binding.buttonCopyLogs.setOnClickListener {
            val logs = adapter.copyText()

            val clipboard =
                context.getSystemService(
                    ClipboardManager::class.java
                )

            clipboard.setPrimaryClip(
                ClipData.newPlainText(
                    context.getString(
                        R.string.clipboard_logs_label
                    ),
                    logs
                )
            )

            Toast.makeText(
                context,
                context.getString(
                    R.string.logs_copied
                ),
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    fun render(
        entries: List<TunnelLogEntry>
    ) {
        adapter.replace(entries)

        val hasEntries = entries.isNotEmpty()

        binding.listLogs.isVisible = hasEntries
        binding.textLogEmpty.isVisible = !hasEntries

        binding.textLogCount.text =
            context.resources.getQuantityString(
                R.plurals.log_count,
                entries.size,
                entries.size
            )

        if (hasEntries) {
            binding.listLogs.post {
                binding.listLogs.scrollToPosition(
                    entries.lastIndex
                )
            }
        }
    }

    fun release() {
        binding.listLogs.adapter = null
    }

    private inner class LogAdapter :
        RecyclerView.Adapter<LogViewHolder>() {

        private val entries =
            mutableListOf<TunnelLogEntry>()

        fun replace(
            newEntries: List<TunnelLogEntry>
        ) {
            val oldSize =
                entries.size

            val isSingleAppend =
                newEntries.size == oldSize + 1 &&
                    (
                        oldSize == 0 ||
                            entries ==
                            newEntries.dropLast(1)
                    )

            if (isSingleAppend) {
                entries.add(
                    newEntries.last()
                )

                notifyItemInserted(
                    entries.lastIndex
                )

                return
            }

            entries.clear()
            entries.addAll(newEntries)
            notifyDataSetChanged()
        }

        fun copyText(): String =
            entries.joinToString("\n") { entry ->
                formatLine(entry)
            }

        override fun onCreateViewHolder(
            parent: ViewGroup,
            viewType: Int
        ): LogViewHolder {
            val itemBinding =
                ItemLogEventBinding.inflate(
                    LayoutInflater.from(
                        parent.context
                    ),
                    parent,
                    false
                )

            return LogViewHolder(itemBinding)
        }

        override fun onBindViewHolder(
            holder: LogViewHolder,
            position: Int
        ) {
            holder.bind(entries[position])
        }

        override fun getItemCount(): Int =
            entries.size
    }

    private inner class LogViewHolder(
        private val itemBinding:
            ItemLogEventBinding
    ) : RecyclerView.ViewHolder(
        itemBinding.root
    ) {

        fun bind(
            entry: TunnelLogEntry
        ) {
            itemBinding.textLogEvent.text =
                formatLine(entry)

            val color =
                if (
                    entry.level ==
                    TunnelLogLevel.ERROR
                ) {
                    ContextCompat.getColor(
                        context,
                        com.google.android.material.R.color
                            .design_default_color_error
                    )
                } else {
                    ContextCompat.getColor(
                        context,
                        com.google.android.material.R.color
                            .material_on_surface_emphasis_medium
                    )
                }

            itemBinding.textLogEvent
                .setTextColor(color)
        }
    }

    private fun formatLine(
        entry: TunnelLogEntry
    ): String {
        val time =
            TIME_FORMATTER.format(
                Date(entry.timestamp)
            )

        return "$time  ${entry.message}"
    }

    private companion object {
        val TIME_FORMATTER =
            SimpleDateFormat(
                "HH:mm",
                Locale.getDefault()
            )
    }
}
