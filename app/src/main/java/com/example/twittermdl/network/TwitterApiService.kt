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
        @Suppress("UNUSED_PARAMETER") credentials: UserCredentials? = null
    ): Result<TweetData> {
        return try {
            val tweetId = extractTweetId(url)
                ?: return Result.failure(Exception("Invalid Twitter URL. Please use format: https://twitter.com/user/status/123456"))

            var tweetData: TweetData? = null

            // Try multiple methods
            // Method 1: Use FxTwitter API (most reliable for media extraction)
            try {
                tweetData = fetchFromFxTwitter(tweetId, url)
            } catch (e: Exception) {
                android.util.Log.w("TwitterAPI", "FxTwitter failed: ${e.message}")
            }

            // Method 2: Use VxTwitter as fallback
            if (tweetData == null) {
                try {
                    tweetData = fetchFromVxTwitter(tweetId, url)
                } catch (e: Exception) {
                    android.util.Log.w("TwitterAPI", "VxTwitter failed: ${e.message}")
                }
            }

            // If we got tweet data but videos don't have variants, try to fetch them from Twitter's syndication API
            val finalTweetData = if (tweetData != null) {
                val videosWithoutVariants = tweetData.mediaItems.filter {
                    it.type == MediaType.VIDEO && it.videoVariants.isNullOrEmpty()
                }

                if (videosWithoutVariants.isNotEmpty()) {
                    android.util.Log.d("TwitterAPI", "Fetching video variants from syndication API...")
                    try {
                        val variantsData = fetchVideoVariantsFromSyndication(tweetId)
                        if (variantsData != null) {
                            // Update media items with variants
                            val updatedMediaItems = tweetData.mediaItems.map { media ->
                                if (media.type == MediaType.VIDEO && media.videoVariants.isNullOrEmpty()) {
                                    media.copy(videoVariants = variantsData)
                                } else {
                                    media
                                }
                            }
                            tweetData.copy(mediaItems = updatedMediaItems)
                        } else {
                            tweetData
                        }
                    } catch (e: Exception) {
                        android.util.Log.w("TwitterAPI", "Failed to fetch variants: ${e.message}")
                        tweetData
                    }
                } else {
                    tweetData
                }
            } else {
                null
            }

            if (finalTweetData != null) {
                return Result.success(finalTweetData)
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

    private fun determineQuality(bitrate: Int, contentType: String): String? {
        return when {
            // Check content type first
            contentType.contains("audio", ignoreCase = true) -> "Audio only (${bitrate/1000} kbps)"

            // Video quality based on bitrate
            bitrate >= 2000000 -> "1080p (${bitrate/1000} kbps)"
            bitrate >= 1000000 -> "720p (${bitrate/1000} kbps)"
            bitrate >= 500000 -> "480p (${bitrate/1000} kbps)"
            bitrate >= 200000 -> "360p (${bitrate/1000} kbps)"
            bitrate > 0 -> "Low quality (${bitrate/1000} kbps)"

            // Fallback for missing bitrate info
            contentType.contains("video", ignoreCase = true) -> "Video (quality unknown)"
            else -> null // Mark as invalid
        }
    }

    private fun isValidDirectMediaUrl(url: String): Boolean {
        // Filter out non-direct media URLs
        return url.isNotEmpty() &&
               !url.contains(".m3u8", ignoreCase = true) && // Exclude HLS playlists
               !url.contains(".mpd", ignoreCase = true) &&  // Exclude DASH manifests
               (url.contains(".mp4", ignoreCase = true) ||
                url.contains(".mp3", ignoreCase = true) ||
                url.contains(".m4a", ignoreCase = true) ||
                url.contains("video/mp4", ignoreCase = true) ||
                url.contains("audio", ignoreCase = true))
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
                            val duration = video.optLong("duration", 0)

                            if (videoUrl.isNotEmpty()) {
                                val type = if (format.contains("gif", ignoreCase = true))
                                    MediaType.GIF else MediaType.VIDEO

                                // Try to extract video variants if available
                                val variants = mutableListOf<VideoVariant>()
                                val variantsArray = video.optJSONArray("variants")

                                if (variantsArray != null && variantsArray.length() > 0) {
                                    for (j in 0 until variantsArray.length()) {
                                        val variant = variantsArray.optJSONObject(j)
                                        if (variant != null) {
                                            val variantUrl = variant.optString("url", "")
                                            val contentType = variant.optString("content_type", "")
                                            val bitrate = variant.optInt("bitrate", 0)

                                            // Only add valid direct media URLs with recognized quality
                                            if (isValidDirectMediaUrl(variantUrl)) {
                                                val quality = determineQuality(bitrate, contentType)
                                                if (quality != null) {
                                                    variants.add(VideoVariant(variantUrl, quality, bitrate, contentType))
                                                    android.util.Log.d("TwitterAPI", "Added variant: $quality - $variantUrl")
                                                } else {
                                                    android.util.Log.w("TwitterAPI", "Skipping variant with unrecognized quality: bitrate=$bitrate, type=$contentType")
                                                }
                                            } else {
                                                android.util.Log.w("TwitterAPI", "Skipping invalid/indirect URL: $variantUrl")
                                            }
                                        }
                                    }

                                    // Sort by bitrate descending (highest quality first)
                                    variants.sortByDescending { it.bitrate }
                                }

                                mediaItems.add(MediaItem(
                                    url = videoUrl,
                                    type = type,
                                    thumbnailUrl = thumbUrl.ifEmpty { null },
                                    duration = if (duration > 0) duration else null,
                                    videoVariants = if (variants.isNotEmpty()) variants else null
                                ))
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

    /**
     * Fetch video variants (different quality options) from Twitter's syndication API
     * Returns list of VideoVariant with different quality options, or null if fetch fails
     */
    private suspend fun fetchVideoVariantsFromSyndication(tweetId: String): List<VideoVariant>? {
        return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val syndicationUrl = "https://cdn.syndication.twimg.com/tweet-result?id=$tweetId&lang=en&features=tfw_timeline_list%3A%3Btfw_follower_count_sunset%3Atrue%3Btfw_tweet_edit_backend%3Aon%3Btfw_refsrc_session%3Aon%3Btfw_show_business_verified_badge%3Aon%3Btfw_mixed_media_15897%3Atreatment%3Btfw_show_business_affiliate_badge%3Aon%3Btfw_show_organic_conversation_thread%3Aoff%3Btfw_show_user_verified_badge%3Aon%3Btfw_duplicate_scribes_to_settings%3Aon%3Btfw_show_blue_verified_badge%3Aon"

                val request = Request.Builder()
                    .url(syndicationUrl)
                    .addHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                    .build()

                val response = okHttpClient.newCall(request).execute()

                if (!response.isSuccessful) {
                    android.util.Log.w("TwitterAPI", "Syndication API returned ${response.code}")
                    return@withContext null
                }

                val responseBody = response.body?.string()
                if (responseBody.isNullOrEmpty()) {
                    android.util.Log.w("TwitterAPI", "Empty response from syndication API")
                    return@withContext null
                }

                val json = JSONObject(responseBody)

                // Check if there's an error
                if (json.has("error")) {
                    android.util.Log.w("TwitterAPI", "Syndication API error: ${json.getString("error")}")
                    return@withContext null
                }

                // Parse media items to find video variants
                val entities = json.optJSONObject("entities")
                val mediaArray = entities?.optJSONArray("media")

                if (mediaArray != null && mediaArray.length() > 0) {
                    for (i in 0 until mediaArray.length()) {
                        val media = mediaArray.getJSONObject(i)
                        val mediaType = media.optString("type")

                        if (mediaType == "video" || mediaType == "animated_gif") {
                            val videoInfo = media.optJSONObject("video_info")
                            val variantsArray = videoInfo?.optJSONArray("variants")

                            if (variantsArray != null && variantsArray.length() > 0) {
                                val variants = mutableListOf<VideoVariant>()

                                for (j in 0 until variantsArray.length()) {
                                    val variant = variantsArray.getJSONObject(j)
                                    val contentType = variant.optString("content_type")
                                    val url = variant.optString("url")
                                    val bitrate = variant.optInt("bitrate", 0)

                                    // Only add valid direct media URLs with recognized quality
                                    if (isValidDirectMediaUrl(url)) {
                                        val quality = determineQuality(bitrate, contentType)
                                        if (quality != null) {
                                            variants.add(VideoVariant(url, quality, bitrate, contentType))
                                            android.util.Log.d("TwitterAPI", "Added variant from syndication: $quality - $url")
                                        } else {
                                            android.util.Log.w("TwitterAPI", "Skipping variant with unrecognized quality: bitrate=$bitrate, type=$contentType")
                                        }
                                    } else {
                                        android.util.Log.w("TwitterAPI", "Skipping invalid/indirect URL from syndication: $url")
                                    }
                                }

                                // Sort by bitrate descending (highest quality first)
                                variants.sortByDescending { it.bitrate }

                                if (variants.isNotEmpty()) {
                                    android.util.Log.d("TwitterAPI", "Found ${variants.size} valid video variants from syndication")
                                    return@withContext variants
                                }
                            }
                        }
                    }
                }

                null
            } catch (e: Exception) {
                android.util.Log.w("TwitterAPI", "Error fetching video variants: ${e.message}")
                null
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
