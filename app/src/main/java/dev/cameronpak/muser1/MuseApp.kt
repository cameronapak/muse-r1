package dev.cameronpak.muser1

import android.app.Application

class MuseApp : Application() {
    val store by lazy { CredentialStore(this) }
    internal val displayHistory by lazy { DisplayHistory(this) }
}
