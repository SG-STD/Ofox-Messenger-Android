package com.sgstudio.OfoxMessenger.fragments

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import com.sgstudio.OfoxMessenger.ChatActivity
import com.sgstudio.OfoxMessenger.R
import com.sgstudio.OfoxMessenger.adapters.UserAdapter
import com.sgstudio.OfoxMessenger.databinding.BottomSheetNewChatBinding
import com.sgstudio.OfoxMessenger.models.User
import java.util.UUID

class NewChatBottomSheetFragment : BottomSheetDialogFragment() {

    private var _binding: BottomSheetNewChatBinding? = null
    private val binding get() = _binding!!
    
    private lateinit var userAdapter: UserAdapter
    private val users = mutableListOf<User>()
    private val TAG = "NewChatBottomSheet"

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = BottomSheetNewChatBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        // Настройка RecyclerView
        setupRecyclerView()
        
        // Настройка поиска
        setupSearch()
        
        // Загрузка пользователей
        loadUsers()
    }

    private fun setupRecyclerView() {
        userAdapter = UserAdapter(users) { user ->
            // Обработка выбора пользователя
            createChat(user)
        }
        
        binding.usersRecyclerView.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = userAdapter
        }
    }

    private fun setupSearch() {
        binding.searchEditText.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                filterUsers(s.toString())
            }
            
            override fun afterTextChanged(s: Editable?) {}
        })
    }

    private fun filterUsers(query: String) {
        if (query.isEmpty()) {
            userAdapter.updateUsers(users)
            return
        }
        
        val filteredUsers = users.filter { user ->
            user.nickname.contains(query, ignoreCase = true) ||
            user.email.contains(query, ignoreCase = true)
        }
        
        userAdapter.updateUsers(filteredUsers)
        
        // Показываем сообщение, если пользователи не найдены
        binding.noUsersFoundText.visibility = if (filteredUsers.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun loadUsers() {
        showProgress(true)
        
        val database = FirebaseDatabase.getInstance()
        val usersRef = database.getReference("users")
        
        // Получаем текущего пользователя
        val prefs = requireContext().getSharedPreferences("user_session", Context.MODE_PRIVATE)
        val currentUserId = prefs.getString("user_id", "") ?: ""
        val currentNickname = prefs.getString("nickname", "") ?: ""
        
        usersRef.addListenerForSingleValueEvent(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                showProgress(false)
                users.clear()
                
                if (!snapshot.exists()) {
                    binding.noUsersFoundText.visibility = View.VISIBLE
                    return
                }
                
                for (userSnapshot in snapshot.children) {
                    val userId = userSnapshot.key ?: continue
                    
                    // Пропускаем текущего пользователя
                    if (userId == currentUserId) continue
                    
                    val nickname = userSnapshot.child("nickname").getValue(String::class.java) ?: ""
                    val email = userSnapshot.child("email").getValue(String::class.java) ?: ""
                    val profilePicture = userSnapshot.child("profile_picture").getValue(String::class.java) ?: ""
                    val status = userSnapshot.child("status").getValue(String::class.java) ?: ""
                    
                    val user = User(
                        id = userId,
                        nickname = nickname,
                        email = email,
                        profilePicture = profilePicture,
                        status = status
                    )
                    
                    users.add(user)
                }
                
                // Обновляем адаптер
                userAdapter.updateUsers(users)
                
                // Показываем сообщение, если пользователи не найдены
                binding.noUsersFoundText.visibility = if (users.isEmpty()) View.VISIBLE else View.GONE
            }

            override fun onCancelled(error: DatabaseError) {
                showProgress(false)
                Log.e(TAG, "Failed to load users", error.toException())
                binding.noUsersFoundText.visibility = View.VISIBLE
                binding.noUsersFoundText.text = getString(R.string.error_loading_users)
            }
        })
    }

    private fun createChat(user: User) {
        showProgress(true)
        
        val database = FirebaseDatabase.getInstance()
        
        // Получаем текущего пользователя
        val prefs = requireContext().getSharedPreferences("user_session", Context.MODE_PRIVATE)
        val currentNickname = prefs.getString("nickname", "") ?: ""
        
        // Создаем ID чата (комбинация никнеймов)
        val chatId = if (currentNickname < user.nickname) {
            "$currentNickname-${user.nickname}"
        } else {
            "${user.nickname}-$currentNickname"
        }
        
        // Проверяем, существует ли уже такой чат
        val chatsRef = database.getReference("chats")
        
        chatsRef.child(chatId).addListenerForSingleValueEvent(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                if (snapshot.exists()) {
                    // Чат уже существует, открываем его
                    openChat(chatId, user.nickname)
                } else {
                    // Создаем новый чат
                    createNewChat(chatId, currentNickname, user.nickname)
                }
            }

            override fun onCancelled(error: DatabaseError) {
                showProgress(false)
                Log.e(TAG, "Failed to check chat existence", error.toException())
            }
        })
    }

    private fun createNewChat(chatId: String, currentNickname: String, otherNickname: String) {
        val database = FirebaseDatabase.getInstance()
        val chatsRef = database.getReference("chats")
        
        // Создаем первое сообщение
        val messageId = "-" + UUID.randomUUID().toString().substring(0, 18)
        val timestamp = System.currentTimeMillis()
        
        val message = hashMapOf(
            "messageId" to messageId,
            "sender" to currentNickname,
            "text" to "Привет! Давай начнем общение.",
            "timestamp" to timestamp,
            "status" to "sent"
        )
        
        // Создаем структуру чата
        val chatData = hashMapOf(
            "participants" to hashMapOf(
                currentNickname to true,
                otherNickname to true
            ),
            "messages" to hashMapOf(
                messageId to message
            ),
            "lastMessage" to message,
            "createdAt" to timestamp
        )
        
        // Сохраняем чат в базу данных
        chatsRef.child(chatId).setValue(chatData)
            .addOnSuccessListener {
                showProgress(false)
                openChat(chatId, otherNickname)
            }
            .addOnFailureListener { e ->
                showProgress(false)
                Log.e(TAG, "Failed to create chat", e)
            }
    }

    private fun openChat(chatId: String, otherNickname: String) {
        // Открываем экран чата
        val intent = Intent(requireContext(), ChatActivity::class.java).apply {
            putExtra("CHAT_ID", chatId)
            putExtra("OTHER_USER", otherNickname)
        }
        startActivity(intent)
        dismiss() // Закрываем BottomSheet
    }

    private fun showProgress(show: Boolean) {
        binding.progressBar.visibility = if (show) View.VISIBLE else View.GONE
        binding.usersRecyclerView.visibility = if (show) View.GONE else View.VISIBLE
        binding.noUsersFoundText.visibility = View.GONE
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        const val TAG = "NewChatBottomSheet"
        
                fun newInstance(): NewChatBottomSheetFragment {
            return NewChatBottomSheetFragment()
        }
    }
}