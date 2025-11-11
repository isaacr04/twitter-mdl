package com.example.twittermdl.network

import com.example.twittermdl.data.*
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.logging.HttpLoggingInterceptor
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class TwitterApiService {
    private val loggingInterceptor = HttpLoggingInterceptor().apply {
        level = HttpLoggingInterceptor.Level.BASIC
    }

    private val okHttpClient = OkHttpClient.Builder()
        .addInterceptor(loggingInterceptor)
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    /**
     * Parse Twitter/X URL to extract tweet data
     * Uses Twitter's syndication API (no auth required)
     */
    suspend fun getTweetDataFromUrl(
        url: String,
        credentials: UserCredentials? = null
    ): Result<TweetData> {
        return try {
            val tweetId = extractTweetId(url)
                ?: return Result.failure(Exception("Invalid Twitter URL. Please use format: https://twitter.com/user/status/123456"))

            // Try multiple methods
            // Method 1: Use FxTwitter API (most reliable for media extraction)
            try {
                val tweetData = fetchFromFxTwitter(tweetId, url)
                return Result.success(tweetData)
            } catch (e: Exception) {
                android.util.Log.w("TwitterAPI", "FxTwitter failed: ${e.message}")
            }

            // Method 2: Use VxTwitter as fallback
            try {
                val tweetData = fetchFromVxTwitter(tweetId, url)
                return Result.success(tweetData)
            } catch (e: Exception) {
                android.util.Log.w("TwitterAPI", "VxTwitter failed: ${e.message}")
            }

            // If all methods fail
            throw Exception("Unable to fetch tweet data. The tweet may be private, deleted, or the API is unavailable.")
        } catch (e: Exception) {
            Result.failure(Exception("Failed to fetch tweet: ${e.message}", e))
        }
    }

    private fun extractTweetId(url: String): String? {
        // Match patterns like:
        // https://twitter.com/user/status/1234567890
        // https://x.com/user/status/1234567890
        val regex = """(?:twitter\.com|x\.com)/(?:\w+)/status/(\d+)""".toRegex()
        return regex.find(url)?.groupValues?.get(1)
    }

    private suspend fun fetchFromFxTwitter(tweetId: String, tweetUrl: String): TweetData {
        return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            try {
                // Convert twitter.com URL to api.fxtwitter.com
                val fxUrl = tweetUrl.replace("twitter.com", "api.fxtwitter.com")
                    .replace("x.com", "api.fxtwitter.com")

                val request = Request.Builder()
                    .url(fxUrl)
                    .addHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                    .build()

                val response = okHttpClient.newCall(request).execute()

                if (!response.isSuccessful) {
                    throw Exception("HTTP ${response.code}: ${response.message}")
                }

                val responseBody = response.body?.string()
                    ?: throw Exception("Empty response")

                android.util.Log.d("TwitterAPI", "FxTwitter Response: ${responseBody.take(500)}")

                val json = JSONObject(responseBody)
                val tweet = json.getJSONObject("tweet")

                // Parse author information
                val author = tweet.optJSONObject("author")
                val authorName = author?.optString("name", "Unknown User") ?: "Unknown User"
                val authorUsername = author?.optString("screen_name", "unknown") ?: "unknown"
                val text = tweet.optString("text", "No text available")

                // Parse media
                val mediaItems = mutableListOf<MediaItem>()
                val media = tweet.optJSONObject("media")

                if (media != null) {
                    val photos = media.optJSONArray("photos")
                    val videos = media.optJSONArray("videos")

                    // Process photos
                    if (photos != null) {
                        for (i in 0 until photos.length()) {
                            val photo = photos.getJSONObject(i)
                            val photoUrl = photo.optString("url")
                            if (photoUrl.isNotEmpty()) {
                                mediaItems.add(MediaItem(photoUrl, MediaType.IMAGE, photoUrl))
                            }
                        }
                    }

                    // Process videos
                    if (videos != null) {
                        for (i in 0 until videos.length()) {
                            val video = videos.getJSONObject(i)
                            val videoUrl = video.optString("url")
                            val thumbUrl = video.optString("thumbnail_url")
                            val format = video.optString("format", "")

                            if (videoUrl.isNotEmpty()) {
                                val type = if (format.contains("gif", ignoreCase = true))
                                    MediaType.GIF else MediaType.VIDEO
                                mediaItems.add(MediaItem(videoUrl, type, thumbUrl.ifEmpty { null }))
                            }
                        }
                    }
                }

                TweetData(
                    tweetId = tweetId,
                    tweetUrl = tweetUrl,
                    authorName = authorName,
                    authorUsername = "@$authorUsername",
                    text = text,
                    mediaItems = mediaItems,
                    isSensitive = false
                )
            } catch (e: Exception) {
                throw Exception("FxTwitter error: ${e.message}", e)
            }
        }
    }

    private suspend fun fetchFromVxTwitter(tweetId: String, tweetUrl: String): TweetData {
        return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            try {
                // VxTwitter provides a direct API
                val vxUrl = "https://api.vxtwitter.com/Twitter/status/$tweetId"

                val request = Request.Builder()
                    .url(vxUrl)
                    .addHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                    .build()

                val response = okHttpClient.newCall(request).execute()

                if (!response.isSuccessful) {
                    throw Exception("HTTP ${response.code}")
                }

                val responseBody = response.body?.string() ?: throw Exception("Empty response")
                android.util.Log.d("TwitterAPI", "VxTwitter Response: ${responseBody.take(500)}")

                val json = JSONObject(responseBody)

                val authorName = json.optString("user_name", "Unknown User")
                val authorUsername = json.optString("user_screen_name", "unknown")
                val text = json.optString("text", "No text available")

                val mediaItems = mutableListOf<MediaItem>()

                // Check for media_extended array
                val mediaExtended = json.optJSONArray("media_extended")
                if (mediaExtended != null) {
                    for (i in 0 until mediaExtended.length()) {
                        val media = mediaExtended.getJSONObject(i)
                        val type = media.optString("type")
                        val url = media.optString("url")
                        val thumbUrl = media.optString("thumbnail_url")

                        when (type) {
                            "image" -> mediaItems.add(MediaItem(url, MediaType.IMAGE, thumbUrl.ifEmpty { url }))
                            "video" -> mediaItems.add(MediaItem(url, MediaType.VIDEO, thumbUrl.ifEmpty { null }))
                            "gif" -> mediaItems.add(MediaItem(url, MediaType.GIF, thumbUrl.ifEmpty { null }))
                        }
                    }
                }

                TweetData(
                    tweetId = tweetId,
                    tweetUrl = tweetUrl,
                    authorName = authorName,
                    authorUsername = "@$authorUsername",
                    text = text,
                    mediaItems = mediaItems,
                    isSensitive = false
                )
            } catch (e: Exception) {
                throw Exception("VxTwitter error: ${e.message}", e)
            }
        }
    }

    @Deprecated("Twitter syndication API now returns empty responses")
    private suspend fun fetchFromSyndication(tweetId: String, tweetUrl: String, syndicationUrl: String): TweetData {
        return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val request = Request.Builder()
                    .url(syndicationUrl)
                    .addHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                    .build()

                val response = okHttpClient.newCall(request).execute()

                if (!response.isSuccessful) {
                    throw Exception("HTTP ${response.code}: ${response.message}")
                }

                val responseBody = response.body?.string()
                    ?: throw Exception("Empty response from Twitter API")

                if (responseBody.isEmpty()) {
                    throw Exception("Received empty response body")
                }

                // Log response for debugging
                android.util.Log.d("TwitterAPI", "Response: ${responseBody.take(500)}")

                val json = JSONObject(responseBody)

                // Check if the response contains an error
                if (json.has("error")) {
                    throw Exception("Twitter API error: ${json.getString("error")}")
                }

        // Parse tweet data
        val authorName = json.optJSONObject("user")?.optString("name") ?: "Unknown User"
        val authorUsername = json.optJSONObject("user")?.optString("screen_name") ?: "unknown"
        val text = json.optString("text", "No text available")
        val isSensitive = json.optBoolean("possibly_sensitive", false)

        // Parse media items
        val mediaItems = mutableListOf<MediaItem>()
        val entities = json.optJSONObject("entities")
        val mediaArray = entities?.optJSONArray("media")

        if (mediaArray != null && mediaArray.length() > 0) {
            for (i in 0 until mediaArray.length()) {
                val media = mediaArray.getJSONObject(i)
                val mediaType = media.optString("type")

                when (mediaType) {
                    "photo" -> {
                        val mediaUrl = media.optString("media_url_https") + "?format=jpg&name=orig"
                        val thumbUrl = media.optString("media_url_https")
                        mediaItems.add(MediaItem(mediaUrl, MediaType.IMAGE, thumbUrl))
                    }
                    "video" -> {
                        val videoInfo = media.optJSONObject("video_info")
                        val variants = videoInfo?.optJSONArray("variants")

                        var bestUrl = ""
                        var maxBitrate = 0

                        if (variants != null) {
                            for (j in 0 until variants.length()) {
                                val variant = variants.getJSONObject(j)
                                if (variant.optString("content_type") == "video/mp4") {
                                    val bitrate = variant.optInt("bitrate", 0)
                                    if (bitrate > maxBitrate) {
                                        maxBitrate = bitrate
                                        bestUrl = variant.optString("url")
                                    }
                                }
                            }
                        }

                        if (bestUrl.isNotEmpty()) {
                            val thumbUrl = media.optString("media_url_https")
                            mediaItems.add(MediaItem(bestUrl, MediaType.VIDEO, thumbUrl))
                        }
                    }
                    "animated_gif" -> {
                        val videoInfo = media.optJSONObject("video_info")
                        val variants = videoInfo?.optJSONArray("variants")

                        if (variants != null && variants.length() > 0) {
                            val variant = variants.getJSONObject(0)
                            val gifUrl = variant.optString("url")
                            val thumbUrl = media.optString("media_url_https")
                            mediaItems.add(MediaItem(gifUrl, MediaType.GIF, thumbUrl))
                        }
                    }
                }
            }
        }

                TweetData(
                    tweetId = tweetId,
                    tweetUrl = tweetUrl,
                    authorName = authorName,
                    authorUsername = "@$authorUsername",
                    text = text,
                    mediaItems = mediaItems,
                    isSensitive = isSensitive
                )
            } catch (e: Exception) {
                throw Exception("Error parsing tweet data: ${e.message ?: e.javaClass.simpleName}", e)
            }
        }
    }

}
