package com.boostt1d.android.legal

/**
 * The Privacy Policy and Terms of Use, ported from the iOS app.
 *
 * These are the documents the setup checkboxes refer to, so the wording is the iOS
 * wording — a consent recorded against different text is not the consent it claims to be.
 * Where Android genuinely differs the sentence is adapted rather than copied (the
 * Keystore rather than the Keychain), and features this build does not have are stated
 * as absent rather than described as present.
 *
 * Anything changed here has to be changed on iOS too, or the two apps are asking people
 * to agree to different things under the same name.
 */
object LegalText {

    data class Section(val title: String, val body: String, val bullets: List<String> = emptyList())

    data class Document(val title: String, val lastUpdated: String, val sections: List<Section>)

    /** Bumped by hand when the wording changes, not derived from the build date. */
    const val LAST_UPDATED = "28 August 2026"

    val privacyPolicy = Document(
        title = "Privacy Policy",
        lastUpdated = LAST_UPDATED,
        sections = listOf(
            Section(
                "Introduction",
                "BoostT1D (\"we,\" \"our,\" or \"us\") is committed to protecting your privacy. " +
                    "This Privacy Policy explains how we collect, use, disclose, and safeguard " +
                    "your information when you use our mobile application BoostT1D (the \"App\"). " +
                    "Please read this Privacy Policy carefully. If you do not agree with the terms " +
                    "of this Privacy Policy, please do not access the App.",
            ),
            Section(
                "Information We Collect",
                "We collect information that you provide directly to us, including:",
                listOf(
                    "Personal information such as your name, email address, age, and location",
                    "Parent or guardian name and email address, if you register as a minor",
                    "Health data including glucose readings, treatments, and therapy profile information",
                    "Your chosen glucose data connectivity method (Nightscout or manual entry) and " +
                        "the associated site address, so we can support that connection. We never " +
                        "collect or transmit your Nightscout access token.",
                ),
            ),
            Section(
                "How We Use Your Information",
                "We use the information we collect to:",
                listOf(
                    "Provide, maintain, and improve the App's functionality",
                    "Process and analyze your health data to provide informational insights",
                    "Respond to your inquiries and provide customer support",
                    "Send you technical notices and support messages",
                    "Send you product news and release updates by email, if the mailing list " +
                        "option is enabled in your registration or Profile settings — you can " +
                        "turn this off at any time",
                ),
            ),
            Section(
                "Data Storage and Security",
                "Your data is stored locally on your device. We implement appropriate technical " +
                    "and organizational measures to protect your personal information:",
                listOf(
                    "Health data is stored on your device, in app-private storage that other " +
                        "applications cannot read",
                    "Connection credentials such as your Nightscout access token are encrypted " +
                        "using the Android Keystore",
                    "This version of the App does not transmit your glucose readings, treatments, " +
                        "or other health data to our servers. Data leaves your device only to " +
                        "reach the Nightscout site you configure, which is your own server.",
                    "We never collect or transmit your Nightscout access token",
                ),
            ),
            Section(
                "Third-Party Services",
                "The App may use third-party services that have their own privacy policies:",
                listOf(
                    "Nightscout: if you configure a Nightscout connection, the App reads from the " +
                        "site you name, according to that site's own privacy practices. BoostT1D " +
                        "reads from Nightscout and never writes to it.",
                ),
            ),
            Section(
                "Data Sharing",
                "We do not sell, trade, or rent your personal information to third parties. We may " +
                    "share your information only in the following circumstances:",
                listOf(
                    "With your explicit consent",
                    "To comply with legal obligations or respond to lawful requests",
                    "To protect our rights, privacy, safety, or property",
                    "In connection with a business transfer or merger",
                ),
            ),
            Section(
                "Your Rights",
                "You have the right to:",
                listOf(
                    "Access your personal data stored in the App",
                    "Delete your data by uninstalling the App or clearing its storage",
                    "Opt out of certain data collection by not using specific features",
                    "Request information about how your data is used",
                ),
            ),
            Section(
                "Children's Privacy",
                "The App is not intended for children under the age of 13. We do not knowingly " +
                    "collect personal information from children under 13. If you are a parent or " +
                    "guardian and believe your child has provided us with personal information, " +
                    "please contact us so we can delete such information.",
            ),
            Section(
                "Changes to This Privacy Policy",
                "We may update this Privacy Policy from time to time. We will notify you of any " +
                    "changes by posting the new Privacy Policy in the App and updating the \"Last " +
                    "Updated\" date. You are advised to review this Privacy Policy periodically " +
                    "for any changes.",
            ),
            Section(
                "Contact Us",
                "If you have any questions about this Privacy Policy, please contact us at " +
                    "info@boostt1d.com, or visit https://boostt1d.com.",
            ),
        ),
    )

    val termsOfUse = Document(
        title = "Terms of Use",
        lastUpdated = LAST_UPDATED,
        sections = listOf(
            Section(
                "Agreement to Terms",
                "By downloading, installing, accessing, or using the BoostT1D mobile application " +
                    "(the \"App\"), you agree to be bound by these Terms of Use (\"Terms\"). If " +
                    "you do not agree to these Terms, do not use the App.",
            ),
            Section(
                "Medical Disclaimer",
                "IMPORTANT: BoostT1D is a health data tracking and analysis tool. This App is not " +
                    "intended to diagnose, treat, cure, or prevent any disease or medical " +
                    "condition.\n\n" +
                    "All information, content, and features provided by this App are for " +
                    "informational and educational purposes only and should not replace " +
                    "professional medical advice, diagnosis, or treatment.\n\n" +
                    "Always consult with qualified healthcare professionals before making any " +
                    "changes to your diabetes management plan, medication, or treatment regimen. " +
                    "In case of a medical emergency, contact your healthcare provider or emergency " +
                    "services immediately. Do not rely on this App for emergency medical situations.",
            ),
            Section(
                "Use of the App",
                "You agree to use the App only for lawful purposes and in accordance with these " +
                    "Terms. You agree not to:",
                listOf(
                    "Use the App in any way that violates any applicable law or regulation",
                    "Use the App to transmit any malicious code or harmful content",
                    "Attempt to gain unauthorized access to any portion of the App",
                    "Interfere with or disrupt the App's functionality",
                    "Use the App for any commercial purpose without our express written consent",
                ),
            ),
            Section(
                "User Accounts and Data",
                "You are responsible for:",
                listOf(
                    "Maintaining the confidentiality of your account information and connection credentials",
                    "All activities that occur under your account",
                    "The accuracy and completeness of the health data you enter",
                    "Backing up your data, as we are not responsible for data loss",
                ),
            ),
            Section(
                "Intellectual Property",
                "The App and its original content, features, and functionality are owned by " +
                    "BoostT1D and are protected by international copyright, trademark, patent, " +
                    "trade secret, and other intellectual property laws. You may not copy, modify, " +
                    "distribute, sell, or lease any part of the App without our prior written consent.",
            ),
            Section(
                "Third-Party Services",
                "The App may integrate with or use third-party services, including Nightscout for " +
                    "reading your glucose data, if you configure it. Your use of these third-party " +
                    "services is subject to their respective terms of service and privacy policies. " +
                    "We are not responsible for the availability, accuracy, or practices of " +
                    "third-party services.",
            ),
            Section(
                "Disclaimer of Warranties",
                "THE APP IS PROVIDED \"AS IS\" AND \"AS AVAILABLE\" WITHOUT WARRANTIES OF ANY KIND, " +
                    "EITHER EXPRESS OR IMPLIED, INCLUDING, BUT NOT LIMITED TO, IMPLIED WARRANTIES " +
                    "OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE, OR NON-INFRINGEMENT. WE " +
                    "DO NOT WARRANT THAT THE APP WILL BE UNINTERRUPTED, SECURE, OR ERROR-FREE.",
            ),
            Section(
                "Limitation of Liability",
                "TO THE MAXIMUM EXTENT PERMITTED BY LAW, IN NO EVENT SHALL BOOSTT1D, ITS " +
                    "DEVELOPERS, OR ITS AFFILIATES BE LIABLE FOR ANY INDIRECT, INCIDENTAL, SPECIAL, " +
                    "CONSEQUENTIAL, OR PUNITIVE DAMAGES, INCLUDING WITHOUT LIMITATION, LOSS OF " +
                    "PROFITS, DATA, USE, OR OTHER INTANGIBLE LOSSES, RESULTING FROM YOUR USE OF THE APP.",
            ),
            Section(
                "Indemnification",
                "You agree to indemnify, defend, and hold harmless BoostT1D, its developers, and " +
                    "affiliates from any claims, damages, losses, liabilities, and expenses " +
                    "(including legal fees) arising out of or relating to your use of the App or " +
                    "violation of these Terms.",
            ),
            Section(
                "Termination",
                "We reserve the right to terminate or suspend your access to the App at any time, " +
                    "with or without cause or notice, for any reason, including if you breach these " +
                    "Terms. Upon termination, your right to use the App will immediately cease.",
            ),
            Section(
                "Changes to Terms",
                "We reserve the right to modify these Terms at any time. We will notify you of any " +
                    "changes by posting the new Terms in the App and updating the \"Last Updated\" " +
                    "date. Your continued use of the App after such modifications constitutes your " +
                    "acceptance of the updated Terms.",
            ),
            Section(
                "Governing Law",
                "These Terms shall be governed by and construed in accordance with the laws of the " +
                    "jurisdiction in which the App is operated, without regard to its conflict of " +
                    "law provisions.",
            ),
            Section(
                "Severability",
                "If any provision of these Terms is found to be unenforceable or invalid, that " +
                    "provision shall be limited or eliminated to the minimum extent necessary, and " +
                    "the remaining provisions shall remain in full force and effect.",
            ),
            Section(
                "Contact Information",
                "If you have any questions about these Terms of Use, please contact us at " +
                    "info@boostt1d.com, or visit https://boostt1d.com.",
            ),
        ),
    )

    /** The medical disclaimer, shown on its own from setup and from About. */
    val medicalDisclaimer = Document(
        title = "Medical Disclaimer",
        lastUpdated = LAST_UPDATED,
        sections = listOf(
            Section(
                "Not medical advice",
                "BoostT1D does not provide medical advice, diagnosis, or treatment " +
                    "recommendations. It is for informational and educational purposes only. " +
                    "Always consult a qualified healthcare professional before making medical " +
                    "decisions.",
            ),
            Section(
                "AI-generated content",
                "BoostT1D uses AI to generate informational outputs (for example, food photo " +
                    "carbohydrate estimates and trend insights). These outputs may be inaccurate " +
                    "and are not a substitute for professional medical judgment.",
            ),
            Section(
                "User responsibility",
                "You are solely responsible for your health decisions. Do not make changes to " +
                    "medication, dosing, diet, or treatment based only on information shown in the " +
                    "app. Consult a qualified healthcare professional.",
            ),
            Section(
                "Emergency situations",
                "BoostT1D is NOT designed for emergency use. In case of severe hypoglycemia, " +
                    "hyperglycemia, diabetic ketoacidosis (DKA), or any medical emergency, call " +
                    "emergency services (911 in the US) immediately and follow your emergency " +
                    "action plan.",
            ),
            Section(
                "Healthcare provider consultation",
                "Work with a qualified healthcare professional for questions about diagnosis, " +
                    "treatment, or medication. Do not use this app as a substitute for " +
                    "professional care.",
            ),
        ),
    )
}
