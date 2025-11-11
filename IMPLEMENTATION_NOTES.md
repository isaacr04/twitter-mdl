# Implementation Notes

## Twitter API Integration

The current `TwitterApiService` class provides a basic structure for fetching tweet data. To make this fully functional, you'll need to implement one of the following approaches:

### Option 1: Official Twitter API v2

1. **Register for Twitter Developer Account**:
   - Visit https://developer.twitter.com
   - Create an app to get API credentials (API Key, API Secret)

2. **Implement OAuth 2.0 Authentication**:
   ```kotlin
   // Add to TwitterApiService
   private fun getAuthToken(): String {
       // Implement OAuth 2.0 Bearer Token authentication
       // or OAuth 1.0a with user context
   }
   ```

3. **Use Tweets Lookup Endpoint**:
   ```kotlin
   @GET("2/tweets/{id}")
   suspend fun getTweet(
       @Path("id") tweetId: String,
       @Query("expansions") expansions: String = "attachments.media_keys",
       @Query("media.fields") mediaFields: String = "url,preview_image_url,type,variants"
   ): TweetResponse
   ```

### Option 2: Twitter Syndication API (Guest Token)

The syndication API doesn't require authentication but has limitations:

```kotlin
suspend fun getTweetDataFromSyndication(tweetId: String): TweetData {
    val url = "https://cdn.syndication.twimg.com/tweet-result?id=$tweetId&lang=en"
    val response = okHttpClient.newCall(
        Request.Builder().url(url).build()
    ).execute()

    val json = JSONObject(response.body?.string() ?: "")
    // Parse JSON response to extract media URLs

    return parseTweetResponse(json)
}
```

### Option 3: Web Scraping (Twitter Web API)

This approach uses Twitter's internal GraphQL API:

```kotlin
private suspend fun getGuestToken(): String {
    val request = Request.Builder()
        .url("https://api.twitter.com/1.1/guest/activate.json")
        .post(RequestBody.create(null, ByteArray(0)))
        .addHeader("Authorization", "Bearer TWITTER_BEARER_TOKEN")
        .build()

    val response = okHttpClient.newCall(request).execute()
    val json = JSONObject(response.body?.string() ?: "")
    return json.getString("guest_token")
}
```

### Media URL Extraction

Once you have the tweet data, extract media URLs based on type:

```kotlin
fun parseMediaFromResponse(json: JSONObject): List<MediaItem> {
    val mediaList = mutableListOf<MediaItem>()
    val entities = json.optJSONObject("entities")
    val extendedEntities = json.optJSONObject("extended_entities")

    extendedEntities?.optJSONArray("media")?.let { mediaArray ->
        for (i in 0 until mediaArray.length()) {
            val media = mediaArray.getJSONObject(i)
            val type = media.getString("type")

            when (type) {
                "photo" -> {
                    val url = media.getString("media_url_https") + "?format=jpg&name=orig"
                    mediaList.add(MediaItem(url, MediaType.IMAGE, url))
                }
                "video", "animated_gif" -> {
                    val videoInfo = media.getJSONObject("video_info")
                    val variants = videoInfo.getJSONArray("variants")

                    // Find highest bitrate MP4 variant
                    var bestUrl = ""
                    var maxBitrate = 0

                    for (j in 0 until variants.length()) {
                        val variant = variants.getJSONObject(j)
                        if (variant.getString("content_type") == "video/mp4") {
                            val bitrate = variant.optInt("bitrate", 0)
                            if (bitrate > maxBitrate) {
                                maxBitrate = bitrate
                                bestUrl = variant.getString("url")
                            }
                        }
                    }

                    val thumbUrl = media.getString("media_url_https")
                    val mediaType = if (type == "animated_gif") MediaType.GIF else MediaType.VIDEO
                    mediaList.add(MediaItem(bestUrl, mediaType, thumbUrl))
                }
            }
        }
    }

    return mediaList
}
```

## Handling Sensitive Content

For sensitive/age-restricted content, you'll need authenticated requests:

```kotlin
class AuthenticatedTwitterClient(
    private val username: String,
    private val password: String
) {
    private var authToken: String? = null
    private var ct0: String? = null

    suspend fun login() {
        // Implement Twitter login flow
        // This typically involves:
        // 1. GET /i/flow/login to get flow token
        // 2. POST credentials
        // 3. Handle 2FA if required
        // 4. Extract auth_token and ct0 cookies
    }

    suspend fun getTweetWithAuth(tweetId: String): TweetData {
        // Use authenticated session to access sensitive content
    }
}
```

## Testing

For testing purposes, you can create mock data:

```kotlin
// In TwitterApiService for testing
private fun getMockTweetData(tweetId: String, url: String): TweetData {
    return TweetData(
        tweetId = tweetId,
        tweetUrl = url,
        authorName = "Test User",
        authorUsername = "testuser",
        text = "This is a test tweet with mock media",
        mediaItems = listOf(
            MediaItem(
                url = "https://pbs.twimg.com/media/sample.jpg",
                type = MediaType.IMAGE,
                thumbnailUrl = "https://pbs.twimg.com/media/sample.jpg"
            )
        ),
        isSensitive = false
    )
}
```

## Recommended Libraries

Consider using these libraries to simplify implementation:

1. **twitter-api-kotlin-sdk**: Official Kotlin SDK
   ```kotlin
   implementation("com.twitter:twitter-api-java-sdk:2.0.3")
   ```

2. **AndroidX Security**: For encrypted credential storage
   ```kotlin
   implementation("androidx.security:security-crypto:1.1.0-alpha06")
   ```

3. **Ktor**: Alternative to Retrofit for networking
   ```kotlin
   implementation("io.ktor:ktor-client-android:2.3.5")
   ```

## Rate Limiting

Implement rate limiting to avoid API restrictions:

```kotlin
class RateLimiter(
    private val maxRequests: Int = 15,
    private val windowMinutes: Long = 15
) {
    private val requestTimes = mutableListOf<Long>()

    suspend fun <T> execute(block: suspend () -> T): T {
        waitIfNeeded()
        requestTimes.add(System.currentTimeMillis())
        return block()
    }

    private suspend fun waitIfNeeded() {
        val now = System.currentTimeMillis()
        val windowStart = now - (windowMinutes * 60 * 1000)

        requestTimes.removeAll { it < windowStart }

        if (requestTimes.size >= maxRequests) {
            val oldestRequest = requestTimes.first()
            val waitTime = (windowMinutes * 60 * 1000) - (now - oldestRequest)
            if (waitTime > 0) {
                delay(waitTime)
            }
        }
    }
}
```

## Error Handling

Implement comprehensive error handling:

```kotlin
sealed class ApiResult<out T> {
    data class Success<T>(val data: T) : ApiResult<T>()
    data class Error(val exception: Exception, val message: String) : ApiResult<Nothing>()
    object NetworkError : ApiResult<Nothing>()
    object RateLimitExceeded : ApiResult<Nothing>()
    object TweetNotFound : ApiResult<Nothing>()
    object SensitiveContent : ApiResult<Nothing>()
}
```

## Security Considerations

1. **Never store API keys in code**: Use BuildConfig or local.properties
2. **Encrypt user credentials**: Use EncryptedSharedPreferences
3. **Validate URLs**: Ensure URLs are from twitter.com/x.com domains
4. **Handle tokens securely**: Clear tokens on logout
5. **Use HTTPS**: All network requests should use HTTPS

## Performance Optimization

1. **Caching**: Cache tweet data to reduce API calls
2. **Image compression**: Compress thumbnails for history
3. **Pagination**: Load history in pages for better performance
4. **Background downloads**: Use WorkManager for large downloads
5. **Database indexing**: Add indexes on frequently queried columns
