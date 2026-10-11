package com.duckets.hermesbuddy

import android.view.Gravity
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.duckets.hermesbuddy.databinding.ItemChatBinding

class ChatAdapter : ListAdapter<ChatMessage, ChatAdapter.VH>(DIFF) {

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<ChatMessage>() {
            override fun areItemsTheSame(a: ChatMessage, b: ChatMessage) =
                a.ts == b.ts && a.role == b.role
            override fun areContentsTheSame(a: ChatMessage, b: ChatMessage) = a == b
        }
    }

    inner class VH(val b: ItemChatBinding) : RecyclerView.ViewHolder(b.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val b = ItemChatBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(b)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val msg = getItem(position)
        val ctx = holder.itemView.context
        val lp = holder.b.bubble.layoutParams as FrameLayout.LayoutParams
        when (msg.role) {
            ChatMessage.Role.USER -> {
                lp.gravity = Gravity.END
                holder.b.bubble.setBackgroundColor(
                    ContextCompat.getColor(ctx, android.R.color.holo_blue_dark)
                )
                holder.b.bubble.setTextColor(
                    ContextCompat.getColor(ctx, android.R.color.white)
                )
            }
            ChatMessage.Role.ASSISTANT -> {
                lp.gravity = Gravity.START
                holder.b.bubble.setBackgroundColor(
                    ContextCompat.getColor(ctx, android.R.color.darker_gray)
                )
                holder.b.bubble.setTextColor(
                    ContextCompat.getColor(ctx, android.R.color.white)
                )
            }
            ChatMessage.Role.SYSTEM -> {
                lp.gravity = Gravity.CENTER_HORIZONTAL
                holder.b.bubble.setBackgroundColor(
                    ContextCompat.getColor(ctx, android.R.color.transparent)
                )
                holder.b.bubble.setTextColor(
                    ContextCompat.getColor(ctx, android.R.color.darker_gray)
                )
                holder.b.bubble.textSize = 12f
            }
        }
        holder.b.bubble.layoutParams = lp
        if (msg.role != ChatMessage.Role.SYSTEM) holder.b.bubble.textSize = 15f
        holder.b.bubble.text = msg.text
    }
}
