# Twitter/X Media Downloader

A comprehensive Android application for downloading images, GIFs, audio, and video content from Twitter/X posts.

## Features

### Core Functionality
- **Multi-Media Download**: Download images, GIFs, audio, and video from Twitter/X posts
- **URL-based Download**: Simply paste any Twitter/X post URL to fetch and download media
- **Selective Downloads**: For posts with multiple media items, choose which ones to download
- **Batch Download**: Option to download all media from a post at once

### Authentication
- **Sensitive Content Support**: Login with Twitter credentials to access age-restricted or sensitive content
- **Secure Storage**: Credentials are securely stored using Android DataStore

### Download History
- **Complete History Log**: View all downloaded posts with thumbnails and metadata
- **Rich Metadata**: Each entry includes:
  - Original post URL
  - Author name and username
  - Tweet text content
  - Download timestamp
  - Media thumbnails
- **Redownload Function**: Easily redownload any previously fetched media
- **History Management**: Delete individual download entries

### User Interface
- **Tab Navigation**: Clean three-tab interface
  - Download: Fetch and download media from Twitter URLs
  - History: Browse download history
  - Settings: Manage Twitter account credentials
- **Material Design**: Modern UI following Material Design guidelines
- **Progress Indicators**: Visual feedback during fetch and download operations

## Technical Stack

- **Language**: Kotlin
- **Architecture**: MVVM (Model-View-ViewModel)
- **UI**: ViewBinding, Material Components
- **Database**: Room (SQLite)
- **Networking**: Retrofit, OkHttp
- **Image Loading**: Coil
- **Async Operations**: Kotlin Coroutines, Flow
- **Storage**: DataStore Preferences
- **Min SDK**: 24 (Android 7.0)
- **Target SDK**: 34 (Android 14)

## Project Structure

```
app/src/main/java/com/example/twittermdl/
├── data/                   # Data models and database
│   ├── MediaType.kt
│   ├── MediaItem.kt
│   ├── TweetData.kt
│   ├── DownloadHistory.kt
│   ├── UserCredentials.kt
│   ├── DownloadHistoryDao.kt
│   └── AppDatabase.kt
├── network/               # API and download services
│   ├── TwitterApiService.kt
│   └── MediaDownloader.kt
├── repository/            # Data repository layer
│   └── DownloadRepository.kt
├── ui/                    # UI components
│   ├── MainActivity.kt
│   ├── MainViewModel.kt
│   ├── ViewPagerAdapter.kt
│   ├── DownloadFragment.kt
│   ├── HistoryFragment.kt
│   ├── SettingsFragment.kt
│   ├── MediaAdapter.kt
│   └── DownloadHistoryAdapter.kt
└── utils/                 # Utility classes
    ├── PreferencesManager.kt
    └── JsonUtils.kt
```

## Permissions

The app requires the following permissions:

- **Internet**: For fetching tweet data and downloading media
- **Storage**: For saving downloaded media to device storage
  - Android 12 and below: READ/WRITE_EXTERNAL_STORAGE
  - Android 13+: READ_MEDIA_IMAGES, READ_MEDIA_VIDEO, READ_MEDIA_AUDIO

## Building the App

1. Clone the repository
2. Open in Android Studio (Arctic Fox or newer)
3. Sync Gradle dependencies
4. Build and run on an emulator or physical device

```bash
./gradlew assembleDebug
```

## Installation

The app can be installed on any Android device running Android 7.0 (API 24) or higher.

## Usage

### Downloading Media

1. Open the app and navigate to the "Download" tab
2. Paste a Twitter/X post URL (e.g., `https://twitter.com/user/status/123456789`)
3. Tap "Fetch Tweet" to retrieve post information
4. Select which media items you want to download (or keep all selected)
5. Tap "Download Selected" to save media to your device

### Accessing Sensitive Content

1. Navigate to the "Settings" tab
2. Enter your Twitter username/email and password
3. Tap "Login" to save credentials
4. You can now download media from sensitive/age-restricted posts

### Viewing History

1. Navigate to the "History" tab
2. Browse all previously downloaded posts
3. Tap "Redownload" to download the media again
4. Tap "Delete" to remove an entry from history

## Media Storage Locations

Downloaded media is saved to the following locations:

- **Images/GIFs**: `Pictures/TwitterDownloads/`
- **Videos**: `Movies/TwitterDownloads/`
- **Audio**: `Music/TwitterDownloads/`

## Important Notes

### Twitter API Implementation

The current implementation includes a basic Twitter API service structure. For full functionality, you'll need to:

1. **Implement Twitter API Integration**:
   - Use Twitter's official API with proper authentication
   - Or implement web scraping with proper user-agent headers
   - Consider using third-party libraries like `twitter-api-kotlin-sdk`

2. **Handle Rate Limiting**: Implement proper rate limiting to avoid API restrictions

3. **Parse Media URLs**: Extract the highest quality media URLs from Twitter's API response

### Legal Considerations

- Respect Twitter's Terms of Service
- Only download content you have rights to access
- Consider copyright and fair use when downloading media
- This app is for educational and personal use

### Privacy & Security

- User credentials are stored locally using encrypted DataStore
- No data is sent to external servers (except Twitter API calls)
- Downloaded media is saved to device storage only

## Future Enhancements

Potential features for future versions:

- [ ] Dark theme support
- [ ] Background download service
- [ ] Download queue management
- [ ] Bulk URL processing
- [ ] Export history to CSV
- [ ] Custom download locations
- [ ] Download quality settings
- [ ] Share integration
- [ ] Statistics and analytics
- [ ] Automatic filename customization

## Contributing

Contributions are welcome! Please feel free to submit pull requests or open issues for bugs and feature requests.

## License

This project is provided as-is for educational purposes. Please ensure you comply with Twitter's Terms of Service and applicable laws when using this application.

## Disclaimer

This application is not affiliated with, authorized, maintained, sponsored, or endorsed by Twitter, X Corp, or any of its affiliates or subsidiaries. This is an independent application.

The developers are not responsible for any misuse of this application or any violations of Twitter's Terms of Service.
