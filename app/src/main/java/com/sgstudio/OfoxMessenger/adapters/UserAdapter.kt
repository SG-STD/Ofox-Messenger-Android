package com.sgstudio.OfoxMessenger.adapters

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.google.android.material.imageview.ShapeableImageView
import com.sgstudio.OfoxMessenger.R
import com.sgstudio.OfoxMessenger.models.User

class UserAdapter(
    private var users: List<User>,
    private val onUserClick: (User) -> Unit
) : RecyclerView.Adapter<UserAdapter.UserViewHolder>() {

    class UserViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val avatarImageView: ShapeableImageView = view.findViewById(R.id.avatarImageView)
        val nameTextView: TextView = view.findViewById(R.id.nameTextView)
        val statusTextView: TextView = view.findViewById(R.id.statusTextView)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): UserViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_user, parent, false)
        return UserViewHolder(view)
    }

        override fun onBindViewHolder(holder: UserViewHolder, position: Int) {
        val user = users[position]
        
        // Устанавливаем имя пользователя
        holder.nameTextView.text = user.nickname
        
        // Устанавливаем статус пользователя
        holder.statusTextView.text = if (user.status.isNotEmpty()) user.status else "Online"
        
        // Загружаем аватар пользователя
        if (user.profilePicture.isNotEmpty()) {
            Glide.with(holder.itemView.context)
                .load(user.profilePicture)
                .placeholder(R.drawable.default_avatar)
                .error(R.drawable.default_avatar)
                .into(holder.avatarImageView)
        } else {
            holder.avatarImageView.setImageResource(R.drawable.default_avatar)
        }
        
        // Устанавливаем обработчик нажатия
        holder.itemView.setOnClickListener {
            onUserClick(user)
        }
    }

    override fun getItemCount() = users.size
    
    fun updateUsers(newUsers: List<User>) {
        users = newUsers
        notifyDataSetChanged()
    }
}