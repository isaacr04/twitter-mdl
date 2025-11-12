# Twitter/X Media Downloader

A simple Android application for downloading images, GIFs, audio, and video content from Twitter/X posts that is ad-free.

## Features

### Core Functionality
- **Multi-Media Download**: Download images, GIFs, audio, and video from Twitter/X posts
- **URL-based Download**: Simply paste any Twitter/X post URL to fetch and download media
- **Selective Downloads**: For posts with multiple media items, choose which ones to download
- **Batch Download**: Option to download all media from a post at once

### Download History
- **History Log**: View all downloaded posts with thumbnails and metadata. Each entry includes:
  - Original post URL
  - Author name and username
  - Tweet text content
  - Download timestamp
  - Media thumbnails
- **Redownload Function**: Easily redownload any previously fetched media
- **History Management**: Delete individual download entries

### Authentication
- TBD

## Permissions

The app requires the following permissions:

- **Internet**: For fetching tweet data and downloading media
- **Storage**: For saving downloaded media to device storage
  - Android 12 and below: READ/WRITE_EXTERNAL_STORAGE
  - Android 13+: READ_MEDIA_IMAGES, READ_MEDIA_VIDEO, READ_MEDIA_AUDIO


## Installation

## Releases

Download the [latest release](https://github.com/isaacr04/twitter-mdl/releases/latest) and install the .apk file. Must allow `Install from Unknown Sources`.

The app can be installed on any Android device running Android 7.0 (API 24) or higher.


## Building the App

1. Clone the repository
2. Open in Android Studio (Arctic Fox or newer)
3. Sync Gradle dependencies
4. Build and run on an emulator or physical device

```bash
./gradlew assembleDebug
```

## Usage

### Downloading Media

1. Open the app and navigate to the "Download" tab
2. Paste a Twitter/X post URL (e.g., `https://twitter.com/user/status/123456789`)
3. Tap "Fetch Tweet" to retrieve post information
4. Select which media items you want to download (or keep all selected)
5. Tap "Download Selected" to save media to your device


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
