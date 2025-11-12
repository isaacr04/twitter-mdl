# Building Release APK for GitHub

This guide explains how to build a signed release APK for distribution on GitHub.

## Prerequisites

- JDK 8 or higher installed
- Android SDK configured

## Step 1: Generate Signing Key (First Time Only)

Run this command in the project root directory:

```bash
keytool -genkey -v -keystore twitter-mdl-release.keystore -alias twitter-mdl -keyalg RSA -keysize 2048 -validity 10000
```

You'll be prompted for:
- **Keystore password**: Choose a strong password (remember it!)
- **Key password**: Can be the same as keystore password
- Your name, organization, location, etc.

⚠️ **Important:** Keep `twitter-mdl-release.keystore` safe and NEVER commit it to git!

## Step 2: Create Keystore Configuration

Create a file named `keystore.properties` in the project root with:

```properties
storePassword=YOUR_KEYSTORE_PASSWORD
keyPassword=YOUR_KEY_PASSWORD
keyAlias=twitter-mdl
storeFile=../twitter-mdl-release.keystore
```

Replace `YOUR_KEYSTORE_PASSWORD` and `YOUR_KEY_PASSWORD` with the passwords you chose.

⚠️ **This file is already in .gitignore and should NEVER be committed!**

## Step 3: Build Release APK

### Option A: Using Gradle Command Line

```bash
./gradlew assembleRelease
```

The signed APK will be located at:
```
app/build/outputs/apk/release/app-release.apk
```

### Option B: Using Android Studio

1. Go to **Build > Generate Signed Bundle / APK**
2. Select **APK**
3. Choose your keystore file
4. Enter passwords
5. Select **release** build variant
6. Click **Finish**

## Step 4: Test the APK

Before releasing, install and test the APK:

```bash
adb install app/build/outputs/apk/release/app-release.apk
```

## Step 5: Create GitHub Release

1. Go to your repository on GitHub
2. Click **Releases** > **Create a new release**
3. Create a new tag (e.g., `v1.0.0`)
4. Fill in release title and description
5. Upload `app-release.apk` as a release asset
6. Click **Publish release**

## Version Management

Update version in `app/build.gradle.kts`:

```kotlin
defaultConfig {
    versionCode = 1      // Increment for each release
    versionName = "1.0"  // User-facing version
}
```

- **versionCode**: Integer that must increase with each release
- **versionName**: User-friendly version string (e.g., "1.0", "1.1", "2.0")

## Security Best Practices

✅ **DO:**
- Keep keystore and keystore.properties private and secure
- Back up your keystore file in a safe location
- Use strong, unique passwords
- Increment versionCode with each release

❌ **DON'T:**
- Commit keystore files to git
- Share keystore passwords publicly
- Lose your keystore (you won't be able to update your app!)

## Troubleshooting

### "keystore.properties not found"
- Make sure the file exists in the project root
- Check that it's not in .gitignore's exclusion list

### "Failed to read key from keystore"
- Verify passwords in keystore.properties are correct
- Check that storeFile path points to the correct keystore

### APK won't install
- Make sure you've uninstalled any debug builds first
- Check that the APK is actually signed: `jarsigner -verify -verbose app-release.apk`

## File Locations

```
twitter-mdl/
├── twitter-mdl-release.keystore  # Your signing key (NEVER commit!)
├── keystore.properties            # Signing config (NEVER commit!)
├── app/
│   └── build/
│       └── outputs/
│           └── apk/
│               └── release/
│                   └── app-release.apk  # Your signed APK
```
