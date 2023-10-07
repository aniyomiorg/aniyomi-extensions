# DISCLAIMER

This extension requires you to log in through Google and relies heavily on scraping the website of Google Drive, which may be against their terms of service. Use at your own risk.

# Google Drive

Table of Content
- [FAQ](#FAQ)
  - [How do i add entries?](#how-do-i-add-entries)
  - [What are all these options for drive paths?](#what-are-all-these-options-for-drive-paths)
  - [I added the drive paths but it still get "Enter drive path(s) in extension settings."](#i-added-the-drive-paths-but-it-still-get-enter-drive-paths-in-extension-settings)
  - [I cannot log in through webview](#i-cannot-log-in-through-webview)

## FAQ

### How do I customize info?

The Google Drive Extension allow for editing the same way as [local anime](https://aniyomi.org/docs/guides/local-anime-source/advanced)      .

### How do I add entries?
The Google Drive Extension *only* supports google drive folders, so no shared drives (but folders inside shared drives works fine!). If you have a folder, which contains sub-folders of an anime, such as:
```
https://drive.google.com/drive/folders/some-long-id
├── anime1/
│   ├── episode 1.mkv
│   ├── episode 2.mkv
│   └── ...
└── anime2/
    ├── episode 1.mkv
    ├── episode 2.mkv
    └── ...
```
Then it you should go to extension settings, and add the url there. You can add multiple drive paths by separating them with a semicolon `;`. To select between the paths, open up the extension and click the filter, from there you can select a specific drive.

If you instead have a folder that contains the episodes directly, such as:
```
https://drive.google.com/drive/folders/some-long-id
├── episode 1.mkv
├── episode 2.mkv
└── ...
```
Then you should open the extension, click filters, then paste the folder link in the `Add single folder` filter.

### What are all these options for drive paths?
The extension allows for some options when adding the drive path:
1. You can customize the name of a drive path by prepending the url with [<insert name>]. This will change the display name when selecting different drive paths in filters. Example: `[Weekly episodes]https://drive.google.com/drive/folders/some-long-id`
2. You can limit the recursion depth by adding a `#` to the end of the url together with a number. If you set it to `1`, the extension will not go into any sub-folders when loading episodes. If you set it to `2`, the extension will traverse into any sub-folders, but not sub-folders of sub-folders, and so on and so forth. It's useful if one folder has a separate folder for each seasons that you want to traverse through, but if another folder has separate folder for openings/endings that you *don't* want to traverse through. Example: `https://drive.google.com/drive/folders/some-long-id#3`
3. It is also possible to specify a range of episodes to load. It needs to be added together with the recursion depth as seen in step 2. Note: it only works if the recursion depth is set to `1`. The range is inclusive, so doing #1,2,7 will load the 2nd up to, and including, the 7th item. Example: `https://drive.google.com/drive/folders/some-long-id#1,2,7`

It is possible to mix these options, and they work for both ways to add folders.

### I added the drive paths but it still get "Enter drive path(s) in extension settings."
This can be caused by the caching that Aniyomi does. Reinstalling the extension will fix this issue (reinstalling an extension does not remove any extension settings)

### I cannot log in through webview
Google can sometimes think that webview isn't a secure browser, and will thus refuse to let you log in. There are a few things you can try to mitigate this:
1. In the top right, click the three dots then click `Clear cookies`
2. In the top right, click the three dots then click `Refresh`
3. Click the `Try again` button after the website doesn't let you log in
4. Make sure that your webview is up to date
   
Try a combination of these steps, and after a few tries it should eventually let you log in.
