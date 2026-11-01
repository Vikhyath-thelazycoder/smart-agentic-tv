# API keys

Everyone uses their own keys, and the release apps have none inside. All of these have free plans. Plans change, so check each site.

| Key | Used for | Needed? | Get it |
|---|---|---|---|
| Gemini | Jarvis, and "what's on my screen" | One AI key is needed for Jarvis | [Google AI Studio](https://aistudio.google.com/apikey) |
| Groq | Jarvis backup when Gemini fails or hits a limit | Optional, recommended | [Groq console](https://console.groq.com/keys) |
| TMDB | Search, picks, which app has a title | Needed for Shows | [TMDB settings](https://www.themoviedb.org/settings/api) |
| Watchmode | Opening a title directly in its app | Optional | [Watchmode](https://api.watchmode.com/) |

## Adding them

In the phone app, open the **⋮** menu, then **API keys**, paste them and press **Save**. Blank fields leave the current value alone.

They travel over the encrypted link and stay in the TV app's private storage. The TV only ever tells a phone whether a key is set, never what it is. Anyone paired with your TV can replace your keys, so only pair phones you trust.

Without keys, the remote, typing and simple voice commands still work.

## Building them in

You can also put them in `apps/tv/config.properties` before building. **Then don't share that APK:** anyone can pull the keys out of it. Never commit that file.

## Free plan limits

Gemini's free plan hit its limit after about 15 quick requests in our testing, and Groq then took over by itself. Free plans may use your requests to improve their services, so check their terms. See [Privacy](PRIVACY.md).
