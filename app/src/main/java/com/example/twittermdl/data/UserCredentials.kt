package com.example.twittermdl.data

data class UserCredentials(
    val username: String,
    val password: String,
    val authToken: String? = null,
    val ct0: String? = null
)
