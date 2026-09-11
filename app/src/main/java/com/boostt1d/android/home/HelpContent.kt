package com.boostt1d.android.home

/** One numbered step inside a help chapter. */
data class HelpStep(val title: String, val detail: String)

/** One guide: an icon, a heading, its steps, and an optional closing note. */
data class HelpChapter(
    val id: String,
    val title: String,
    val subtitle: String,
    val steps: List<HelpStep>,
    val note: String? = null,
    /** Offered at the bottom of the chapter when there is somewhere useful to send the reader. */
    val linkLabel: String? = null,
    val linkUrl: String? = null,
) {
    /** Connection guides come first; everything else is about using the app. */
    val isConnectionGuide: Boolean get() = id.startsWith("connect") || id == "manual"
}

/**
 * The setup and usage guides, ported from the iOS HelpContent.
 *
 * Wording follows Android where the two differ: connection settings live on the Data Source
 * screen rather than inside Profile, and secrets go to EncryptedSharedPreferences rather than
 * the Keychain.
 */
object HelpContent {
    val chapters: List<HelpChapter> = listOf(
        HelpChapter(
            id = "connect-nightscout",
            title = "Connect Nightscout",
            subtitle = "Read glucose from your Nightscout site",
            steps = listOf(
                HelpStep(
                    "Open Data Source",
                    "From Menu at the bottom of the home screen, open Data Source.",
                ),
                HelpStep(
                    "Choose Nightscout",
                    "Select Nightscout as your data source. You can also set this during first-time setup.",
                ),
                HelpStep(
                    "Enter your site address",
                    "Use the full address of your Nightscout site, for example " +
                        "https://yourname.up.railway.app. BoostT1D does not host Nightscout for you.",
                ),
                HelpStep(
                    "Enter an access token",
                    "Create or copy a read-only access token from your Nightscout Admin Tools. " +
                        "It lets BoostT1D display your trends and insights, and you can revoke it " +
                        "in Nightscout at any time.",
                ),
                HelpStep(
                    "Set your glucose range",
                    "Choose low and high targets in Profile. They drive time in range and the " +
                        "colors on every chart.",
                ),
                HelpStep(
                    "Test and save",
                    "Tap Test connection, then Save and sync. Return to the Dashboard and your " +
                        "current glucose should appear.",
                ),
            ),
            note = "Need Nightscout first? See nightscout.github.io for setup guides. Keep your token private.",
            linkLabel = "Learn how to set up Nightscout",
            linkUrl = "https://nightscout.github.io",
        ),
        HelpChapter(
            id = "connect-dexcom",
            title = "Connect Dexcom Share",
            subtitle = "Pull readings with your Dexcom Share credentials",
            steps = listOf(
                HelpStep(
                    "Open Data Source",
                    "From Menu at the bottom of the home screen, open Data Source.",
                ),
                HelpStep(
                    "Choose Dexcom",
                    "Select Dexcom as your data source. Nightscout is not required for Dexcom Share.",
                ),
                HelpStep(
                    "Enter your Share username",
                    "Use the email, phone number or Account ID you sign in to the Dexcom app with.",
                ),
                HelpStep(
                    "Enter your password",
                    "Your Dexcom account password. It is stored in EncryptedSharedPreferences, " +
                        "never in the plain settings file.",
                ),
                HelpStep(
                    "Pick the correct region",
                    "Choose the region your Dexcom account is registered in. The hosts do not " +
                        "federate, so the wrong region is the most common cause of a failed sign-in.",
                ),
                HelpStep(
                    "Save and check the Dashboard",
                    "After saving, open the Dashboard and refresh. Recent Share readings should appear.",
                ),
            ),
            note = "Dexcom Share keeps about a day of history and supplies glucose only. Active " +
                "insulin, active carbs and your event log need Nightscout or manual entry.",
        ),
        HelpChapter(
            id = "connect-libre",
            title = "Connect FreeStyle Libre",
            subtitle = "Pull readings through LibreLinkUp sharing",
            steps = listOf(
                HelpStep(
                    "Stream to LibreView first",
                    "In the FreeStyle Libre 3 app, sign in to your LibreView account so readings " +
                        "reach Abbott's cloud. Nothing else can read the sensor until they do.",
                ),
                HelpStep(
                    "Invite a LibreLinkUp connection",
                    "In the FreeStyle Libre app, open Connected Apps, then LibreLinkUp, and invite " +
                        "an email address. Setting this up for yourself? Invite a second email you own.",
                ),
                HelpStep(
                    "Accept in the LibreLinkUp app",
                    "Install Abbott's LibreLinkUp app, sign in with the invited email and accept " +
                        "the invitation. Confirm live glucose shows there: BoostT1D reads exactly " +
                        "what that app sees.",
                ),
                HelpStep(
                    "Enter those credentials in BoostT1D",
                    "On the Data Source screen, choose FreeStyle Libre and enter the LibreLinkUp " +
                        "email and password, not the FreeStyle Libre app login.",
                ),
                HelpStep(
                    "Test the connection",
                    "Tap Test connection. Your LibreView region is detected automatically, so " +
                        "there is no region to pick.",
                ),
            ),
            note = "LibreLinkUp returns about twelve hours of readings per sync, so 3-day and " +
                "7-day charts fill in over the following days. Like Dexcom Share it supplies " +
                "glucose only.",
        ),
        HelpChapter(
            id = "manual",
            title = "Manual glucose entry",
            subtitle = "Use BoostT1D without Nightscout, Dexcom or Libre",
            steps = listOf(
                HelpStep(
                    "Choose Manual entry",
                    "On the Data Source screen, or during setup, select Manual as your glucose source.",
                ),
                HelpStep(
                    "Add readings from the Dashboard",
                    "In manual mode, use Add entry on the glucose card to enter values yourself.",
                ),
                HelpStep(
                    "Log insulin in Event Log and meals in Food Log",
                    "Snap a Meal, What Happened? and the Doctor Visit report all work on the data " +
                        "you enter locally.",
                ),
            ),
            note = "You can switch between Nightscout, Dexcom, Libre and Manual later without " +
                "deleting anything you have logged.",
        ),
        HelpChapter(
            id = "view-data",
            title = "View your glucose data",
            subtitle = "Dashboard, history and charts",
            steps = listOf(
                HelpStep(
                    "Dashboard",
                    "Your latest glucose and trend, active insulin and carbs, and the last 24 " +
                        "hours on one card. Tap refresh to sync.",
                ),
                HelpStep(
                    "BG Log",
                    "Tap Logs in the bottom bar, then BG Log, or tap History on the 24-hour chart, " +
                        "to browse past readings, statistics and trend charts.",
                ),
                HelpStep(
                    "Multi-day views",
                    "Windows longer than a day draw the AGP profile: the median day with its " +
                        "spread, so recurring shapes stand out.",
                ),
                HelpStep(
                    "Target range colors",
                    "Your low and high settings from Profile color every chart and every time in " +
                        "range figure.",
                ),
            ),
        ),
        HelpChapter(
            id = "food-photos",
            title = "Snap a Meal and Food Log",
            subtitle = "Photo carb estimates and saved meals",
            steps = listOf(
                HelpStep("Open Snap a Meal", "Tap Food in the bottom bar, then Snap a Meal."),
                HelpStep(
                    "Take or choose a photo",
                    "Photograph your meal clearly. The app sends the image for an informational " +
                        "carbohydrate estimate, not medical advice.",
                ),
                HelpStep(
                    "Review the estimate",
                    "Check the suggested carbs and description, and adjust anything that looks " +
                        "wrong before saving.",
                ),
                HelpStep(
                    "Save to Food Log",
                    "Save the meal so you can find it later. Carbs recorded in Nightscout are " +
                        "imported into the same list.",
                ),
                HelpStep(
                    "Browse Food Log",
                    "Tap Food in the bottom bar, then Food Log, to revisit past meals or edit them.",
                ),
            ),
            note = "Free photo estimates are limited per day. Estimates can be wrong: use your " +
                "own judgment and your care team's guidance for dosing.",
        ),
        HelpChapter(
            id = "event-log",
            title = "Event Log",
            subtitle = "Insulin and daily events",
            steps = listOf(
                HelpStep(
                    "Open Event Log",
                    "Tap Logs in the bottom bar, then Event Log, to see insulin, temp basals, " +
                        "activity and other events.",
                ),
                HelpStep(
                    "Add an entry",
                    "Log doses, corrections and activity with a time. Meals and carbs belong in " +
                        "Food Log. Accurate timestamps make What Happened? more useful.",
                ),
                HelpStep(
                    "Downloaded rows are read-only",
                    "Anything that came from Nightscout cannot be edited here, because the edit " +
                        "would be overwritten on the next sync without ever reaching your site.",
                ),
                HelpStep(
                    "Use the logs together",
                    "Food Log focuses on meals, Event Log is the broader timeline. Both feed " +
                        "pattern analysis.",
                ),
            ),
        ),
        HelpChapter(
            id = "insights",
            title = "What Happened?",
            subtitle = "Seven-day patterns and therapy review",
            steps = listOf(
                HelpStep(
                    "Open What Happened?",
                    "Tap Insights in the bottom bar, then What Happened? Summary, Patterns, " +
                        "Therapy and Days all describe the same seven completed days.",
                ),
                HelpStep(
                    "Start with Patterns",
                    "Recurring glucose behavior by time of day, with the counts and windows " +
                        "calculated on this device rather than by AI.",
                ),
                HelpStep(
                    "Use the Therapy page",
                    "It explains how basal, correction factor or carb ratio may be contributing, " +
                        "and what bounded change the formula suggests discussing. Automated " +
                        "delivery and manual pumps are read differently.",
                ),
                HelpStep(
                    "One AI review per day",
                    "When AI is on, one daily pass reviews the same seven days. It can prioritize " +
                        "and explain formula-verified findings but never supplies a dose value, " +
                        "and a failed attempt stays on the formula result until the next day.",
                ),
            ),
            note = "BoostT1D never changes pump or automated-delivery settings. Verify every " +
                "value with your care team and your device reports.",
        ),
        HelpChapter(
            id = "reports",
            title = "Doctor Visit",
            subtitle = "A visit-ready clinical report",
            steps = listOf(
                HelpStep(
                    "Build the report",
                    "Tap Insights in the bottom bar, then Doctor Visit. Choose a 7 or 14 day window.",
                ),
                HelpStep(
                    "Review before you share",
                    "Check that the dates and highlights look right, then export a PDF and share " +
                        "it however you like.",
                ),
                HelpStep(
                    "Better reports need better logs",
                    "Glucose history plus Event Log and Food Log entries make the report more " +
                        "specific. Add data as you go.",
                ),
            ),
            note = "Reports are for organization and discussion with your care team, not a " +
                "diagnosis or a treatment plan.",
        ),
        HelpChapter(
            id = "change-settings",
            title = "Change settings later",
            subtitle = "Profile, units and data source",
            steps = listOf(
                HelpStep(
                    "Profile",
                    "Update your name, photo, country, glucose units and target range.",
                ),
                HelpStep(
                    "Insulin Doses",
                    "Basal rates, carb ratio and correction factor. Downloaded from Nightscout " +
                        "when it has them, entered by hand when it does not.",
                ),
                HelpStep(
                    "Replay the short tour",
                    "Open How It Works from the Menu at the bottom of the home screen.",
                ),
                HelpStep(
                    "Need more help?",
                    "Email info@boostt1d.com or visit boostt1d.com. Optional donations are under " +
                        "Support Us in the same menu.",
                ),
            ),
        ),
    )
}
