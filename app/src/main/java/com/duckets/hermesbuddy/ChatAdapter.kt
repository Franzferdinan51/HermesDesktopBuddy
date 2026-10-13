package com.duckets.hermesbuddy

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.duckets.hermesbuddy.databinding.ItemChatBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ChatAdapter : ListAdapter<ChatMessage, ChatAdapter.VH>(DIFF) {

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<ChatMessage>() {
            override fun areItemsTheSame(a: ChatMessage, b: ChatMessage) =
                a.ts == b.ts && a.role == b.role && a.turn == b.turn
            override fun areContentsTheSame(a: ChatMessage, b: ChatMessage) = a == b
        }

        /** Bubbles span at most 78% of the row, Muse-style. */
        private const val MAX_WIDTH_FRACTION = 0.78f

        private val TIME_FMT = SimpleDateFormat("HH:mm", Locale.getDefault())
    }

    inner class VH(val b: ItemChatBinding) : RecyclerView.ViewHolder(b.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val b = ItemChatBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        val minPx = (280 * parent.resources.displayMetrics.density).toInt()
        val maxPx = (parent.measuredWidth * MAX_WIDTH_FRACTION).toInt()
        // parent.measuredWidth is 0 before first layout; fall back then.
        b.bubble.maxWidth = if (maxPx > 0) maxOf(maxPx, minPx) else
            (320 * parent.resources.displayMetrics.density).toInt()
        return VH(b)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val msg = getItem(position)
        val ctx = holder.itemView.context
        val colLp = holder.b.messageCol.layoutParams as FrameLayout.LayoutParams
        val timeLp = holder.b.timestamp.layoutParams as LinearLayout.LayoutParams
        when (msg.role) {
            ChatMessage.Role.USER -> {
                colLp.gravity = Gravity.END
                timeLp.gravity = Gravity.END
                holder.b.bubble.background =
                    ContextCompat.getDrawable(ctx, R.drawable.bubble_user)
                holder.b.bubble.setTextColor(
                    ContextCompat.getColor(ctx, android.R.color.white)
                )
                holder.b.bubble.textSize = 15f
            }
            ChatMessage.Role.ASSISTANT -> {
                colLp.gravity = Gravity.START
                timeLp.gravity = Gravity.START
                holder.b.bubble.background =
                    ContextCompat.getDrawable(ctx, R.drawable.bubble_hermes)
                holder.b.bubble.setTextColor(
                    ContextCompat.getColor(ctx, android.R.color.white)
                )
                holder.b.bubble.textSize = 15f
            }
            ChatMessage.Role.SYSTEM -> {
                colLp.gravity = Gravity.CENTER_HORIZONTAL
                timeLp.gravity = Gravity.CENTER_HORIZONTAL
                holder.b.bubble.background = null
                holder.b.bubble.setTextColor(
                    ContextCompat.getColor(ctx, R.color.hermes_text_muted)
                )
                holder.b.bubble.textSize = 12f
            }
        }
        holder.b.messageCol.layoutParams = colLp
        holder.b.timestamp.layoutParams = timeLp
        holder.b.bubble.text = msg.text
        holder.b.timestamp.text = TIME_FMT.format(Date(msg.ts))
        holder.b.timestamp.visibility =
            if (msg.role == ChatMessage.Role.SYSTEM) View.GONE else View.VISIBLE
        holder.b.bubble.setOnLongClickListener {
            copyToClipboard(ctx, msg.text)
            true
        }
    }

    private fun copyToClipboard(ctx: Context, text: String) {
        try {
            val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("Hermes message", text))
            Toast.makeText(ctx, "Copied", Toast.LENGTH_SHORT).show()
        } catch (_: Exception) {
        }
    }
}
