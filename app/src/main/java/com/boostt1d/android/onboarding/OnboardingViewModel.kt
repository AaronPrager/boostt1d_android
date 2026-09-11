package com.boostt1d.android.onboarding

import android.app.Application
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.boostt1d.android.data.Countries
import com.boostt1d.android.data.CredentialStore
import com.boostt1d.android.data.GlucoseConnectionOption
import com.boostt1d.android.sync.DexcomShareService
import com.boostt1d.android.sync.LibreLinkUpService
import com.boostt1d.android.sync.NightscoutService
import com.boostt1d.android.sync.NightscoutUrl
import com.boostt1d.android.data.GlucoseDisplay
import com.boostt1d.android.data.GlucoseSettings
import com.boostt1d.android.data.PhotoScaling
import com.boostt1d.android.data.ProfileRepository
import com.boostt1d.android.data.UserProfile
import kotlinx.coroutines.Dispatchers
import com.boostt1d.android.ui.UsStates
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Calendar
import java.util.Locale
import java.util.UUID

class OnboardingViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = ProfileRepository(application)
    private val credentials = CredentialStore(application)
    private val nightscout = NightscoutService()
    private val dexcom = DexcomShareService()
    private val libre = LibreLinkUpService()

    var step by mutableStateOf(OnboardingStep.PERSONAL_INFO)
        private set

    var draft by mutableStateOf(OnboardingDraft())
        private set

    /** Set when a step is left with values of the wrong shape; cleared once shown. */
    var problem by mutableStateOf<String?>(null)
        private set

    /** True once setup has been committed, so the host can stop showing this flow. */
    var isComplete by mutableStateOf(false)
        private set

    var testingConnection by mutableStateOf(false)
        private set

    var connectionTestResult by mutableStateOf<String?>(null)
        private set

    /** Null until a test has run; false only for a connection that returns nothing. */
    var connectionTestSucceeded by mutableStateOf<Boolean?>(null)
        private set

    private var hasManuallyChangedUnit = false

    init {
        seedFromLocale()
    }

    fun update(transform: (OnboardingDraft) -> OnboardingDraft) {
        draft = transform(draft)
    }

    /**
     * The unit follows the country until the user overrides it, and then stops
     * following — otherwise picking a country would silently undo a deliberate choice.
     */
    fun selectCountry(code: String, name: String) {
        // A state from a previous country is worse than no state at all.
        val state = if (code == UsStates.COUNTRY_CODE) draft.stateName else ""
        draft = draft.copy(countryCode = code, countryName = name, stateName = state)
        if (!hasManuallyChangedUnit) {
            draft = draft.copy(bgUnit = GlucoseDisplay.defaultUnit(code))
        }
    }

    fun selectState(name: String) {
        draft = draft.copy(stateName = name)
    }

    fun selectUnit(unit: com.boostt1d.android.data.BGUnit) {
        hasManuallyChangedUnit = true
        draft = draft.copy(bgUnit = unit)
    }

    /**
     * Age drives whether a parent or guardian is required, so dropping out of that
     * band has to clear the fields — a stored parent email for a 30-year-old is the
     * kind of leftover nobody goes looking for.
     */
    fun selectAge(age: String) {
        draft = draft.copy(age = age)
        val value = age.toIntOrNull()
        if (value != null && !com.boostt1d.android.data.AgeSelectionOptions.requiresParentGuardian(value)) {
            draft = draft.copy(parentName = "", parentEmail = "")
        }
    }

    /**
     * Probes the site before setup finishes, so a wrong address or token is caught while
     * the user is still looking at the field rather than on an empty dashboard later.
     */
    fun testNightscout() {
        if (testingConnection) return

        viewModelScope.launch {
            testingConnection = true
            connectionTestResult = null

            when (draft.connection) {
                GlucoseConnectionOption.NIGHTSCOUT -> {
                    val url = draft.nightscoutUrl.trim()
                    if (url.isEmpty()) {
                        testingConnection = false
                        return@launch
                    }
                    val report = runCatching {
                        nightscout.testConnection(url, draft.nightscoutToken.trim())
                    }.getOrNull()
                    connectionTestSucceeded = report?.glucoseAvailable
                    connectionTestResult = report?.message(hasToken = draft.nightscoutToken.isNotBlank())
                        ?: "Could not reach the site. Check the address and your connection."
                }

                GlucoseConnectionOption.DEXCOM -> {
                    // A login is the only honest test: Share has no status endpoint, and
                    // anything short of signing in would pass for a wrong region.
                    val result = runCatching {
                        dexcom.login(
                            draft.dexcomUsername.trim(),
                            draft.dexcomPassword,
                            draft.dexcomRegion,
                        )
                    }
                    connectionTestSucceeded = result.isSuccess
                    connectionTestResult = if (result.isSuccess) {
                        "Signed in to Dexcom Share. Readings will start arriving on the next sync."
                    } else {
                        result.exceptionOrNull()?.message
                            ?: "Could not sign in to Dexcom Share."
                    }
                }

                GlucoseConnectionOption.LIBRE -> {
                    val result = runCatching {
                        libre.verify(draft.libreUsername, draft.librePassword, draft.libreRegion)
                    }
                    connectionTestSucceeded = result.isSuccess
                    connectionTestResult = result.fold(
                        onSuccess = { v ->
                            // The redirect resolved the real region; keep it so setup saves it.
                            draft = draft.copy(libreRegion = v.region)
                            "Signed in. Following ${'$'}{v.connectionName}" +
                                (v.latestMgdl?.let { " — latest reading ${'$'}it mg/dL." } ?: ", but no recent reading yet.")
                        },
                        onFailure = { it.message ?: "Could not sign in to LibreLinkUp." },
                    )
                }

                else -> Unit
            }

            testingConnection = false
        }
    }

    fun pickPhoto(uri: Uri) {
        viewModelScope.launch {
            val encoded = withContext(Dispatchers.IO) {
                PhotoScaling.encodeAvatar(getApplication(), uri)
            }
            if (encoded != null) draft = draft.copy(photoBase64 = encoded)
        }
    }

    fun clearPhoto() {
        draft = draft.copy(photoBase64 = null)
    }

    fun consumeProblem() {
        problem = null
    }

    fun back() {
        step.previous?.let { step = it }
    }

    fun advance() {
        OnboardingValidation.problem(step, draft)?.let {
            problem = it
            return
        }

        val next = step.next
        if (next == null) {
            complete()
        } else {
            step = next
        }
    }

    private fun seedFromLocale() {
        val code = Locale.getDefault().country.ifEmpty { "US" }
        val name = Countries.name(code).ifEmpty { "United States" }
        draft = draft.copy(
            countryCode = code,
            countryName = name,
            bgUnit = GlucoseDisplay.defaultUnit(code),
        )
    }

    /**
     * Ages and durations are entered as a number and a bucket but stored as instants,
     * so the profile keeps meaning as time passes rather than freezing "18" forever.
     */
    private fun complete() {
        val now = System.currentTimeMillis()
        val currentYear = Calendar.getInstance().get(Calendar.YEAR)

        val birthYear = currentYear - (draft.ageValue ?: 0)
        val dateOfBirth = januaryFirst(birthYear)

        val dateOfDiagnosis = if (draft.hasDiabetes) {
            januaryFirst(currentYear - OnboardingValidation.yearsValue(draft.yearsSinceDiagnosis))
        } else {
            now
        }

        val resolvedCountry = draft.countryName.ifEmpty {
            Countries.name(Locale.getDefault().country).ifEmpty { "United States" }
        }
        val resolvedCode = draft.countryCode.ifEmpty { Countries.code(resolvedCountry) }

        val profile = UserProfile(
            id = UUID.randomUUID().toString(),
            name = draft.name.trim(),
            photoData = draft.photoBase64,
            country = resolvedCountry,
            countryCode = resolvedCode,
            state = draft.stateName.ifEmpty { null },
            dateOfBirthEpochMillis = dateOfBirth,
            dateOfDiagnosisEpochMillis = dateOfDiagnosis,
            hasDiabetes = draft.hasDiabetes,
            isProfileComplete = true,
            createdAtEpochMillis = now,
            updatedAtEpochMillis = now,
            bgUnit = draft.bgUnit,
            parentName = draft.parentName.trim().ifEmpty { null },
            parentEmail = draft.parentEmail.trim().ifEmpty { null },
            email = draft.email.trim().ifEmpty { null },
            gender = draft.gender.ifEmpty { null },
            marketingOptIn = draft.marketingOptIn,
            therapy = draft.therapy,
            yearsSinceDiagnosisBucket = draft.yearsSinceDiagnosis,
        )

        val settings = GlucoseSettings(
            connection = draft.connection,
            lowGlucose = draft.lowGlucose,
            highGlucose = draft.highGlucose,
            // Normalized once, here, so nothing downstream has to re-derive it.
            nightscoutUrl = if (draft.connection == GlucoseConnectionOption.NIGHTSCOUT) {
                NightscoutUrl.normalize(draft.nightscoutUrl)
            } else {
                ""
            },
            dexcomUsername = if (draft.connection == GlucoseConnectionOption.DEXCOM) {
                draft.dexcomUsername.trim()
            } else {
                ""
            },
            dexcomRegion = draft.dexcomRegion,
            libreUsername = if (draft.connection == GlucoseConnectionOption.LIBRE) {
                draft.libreUsername.trim()
            } else {
                ""
            },
            libreRegion = draft.libreRegion,
        )

        // Credentials go to encrypted storage, never into the settings blob.
        when (draft.connection) {
            GlucoseConnectionOption.NIGHTSCOUT ->
                credentials.nightscoutToken = draft.nightscoutToken.trim()
            GlucoseConnectionOption.DEXCOM ->
                credentials.dexcomPassword = draft.dexcomPassword
            GlucoseConnectionOption.LIBRE ->
                credentials.librePassword = draft.librePassword
            else -> Unit
        }

        // iOS also POSTs demographics to the backend here when the user opted into the
        // mailing list. Deliberately not wired yet: the Android registration endpoint
        // does not exist, and the local profile is what setup is actually for.
        viewModelScope.launch {
            repository.saveProfile(profile)
            repository.saveSettings(settings)
            isComplete = true
        }
    }

    private fun januaryFirst(year: Int): Long = Calendar.getInstance().apply {
        clear()
        set(year, Calendar.JANUARY, 1)
    }.timeInMillis
}
