package com.example.twittermdl.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import com.example.twittermdl.R
import com.example.twittermdl.databinding.FragmentSettingsBinding
import kotlinx.coroutines.launch

class SettingsFragment : Fragment() {

    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!

    private val viewModel: MainViewModel by activityViewModels()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupListeners()
        observeViewModel()
    }

    private fun setupListeners() {
        binding.loginButton.setOnClickListener {
            val username = binding.usernameInput.text.toString().trim()
            val password = binding.passwordInput.text.toString().trim()

            if (username.isNotEmpty() && password.isNotEmpty()) {
                viewModel.saveCredentials(username, password)
                Toast.makeText(
                    requireContext(),
                    "Credentials saved",
                    Toast.LENGTH_SHORT
                ).show()

                // Clear inputs
                binding.usernameInput.text?.clear()
                binding.passwordInput.text?.clear()
            } else {
                Toast.makeText(
                    requireContext(),
                    "Please enter both username and password",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }

        binding.logoutButton.setOnClickListener {
            viewModel.logout()
            Toast.makeText(
                requireContext(),
                "Logged out",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun observeViewModel() {
        lifecycleScope.launch {
            viewModel.isLoggedIn.collect { isLoggedIn ->
                updateLoginStatus(isLoggedIn)
            }
        }

        lifecycleScope.launch {
            viewModel.userCredentials.collect { credentials ->
                credentials?.let {
                    binding.loginStatusText.text = getString(
                        R.string.logged_in_as,
                        it.username
                    )
                }
            }
        }
    }

    private fun updateLoginStatus(isLoggedIn: Boolean) {
        if (isLoggedIn) {
            binding.usernameInputLayout.visibility = View.GONE
            binding.passwordInputLayout.visibility = View.GONE
            binding.loginButton.visibility = View.GONE
            binding.logoutButton.visibility = View.VISIBLE
        } else {
            binding.usernameInputLayout.visibility = View.VISIBLE
            binding.passwordInputLayout.visibility = View.VISIBLE
            binding.loginButton.visibility = View.VISIBLE
            binding.logoutButton.visibility = View.GONE
            binding.loginStatusText.text = getString(R.string.not_logged_in)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
