# Privacy

There are no accounts, no analytics and no servers run by this project.

| What | Goes to | When |
|---|---|---|
| What you ask Jarvis, plus facts about the TV (time, country, app list, saved and recent titles, routines, timers) | Google Gemini, or Groq if that fails | Each request that isn't a simple command |
| A small picture of your screen | Google Gemini | Only when you ask what's on your screen. If Jarvis isn't sure you asked, it checks first |
| Your speech | Your phone's speech service, usually Google | When the mic is on |
| Words you search for | TMDB, and sometimes the AI to fix a misheard title | When you search |
| Title IDs | Watchmode | When finding a link to a title |
| Your location settings | Open-Meteo | For the weather card |

**Free AI plans may use your requests to improve their services.** Check [Google's](https://ai.google.dev/gemini-api/terms) and [Groq's](https://groq.com/privacy-policy/) terms. Don't ask anything you wouldn't want stored.

## Stored on the TV

API keys, paired phones, saved titles, recently opened titles, routines and timers, all in the app's private storage. Uninstalling the app removes them.

## On the phone

The pairing details and your settings. The app only connects while it's open: closed, it doesn't listen or connect, and it has no background service.

While Jarvis is speaking on the phone, the mic is read for loudness only, to notice you talking over it. That sound is neither kept nor sent.
