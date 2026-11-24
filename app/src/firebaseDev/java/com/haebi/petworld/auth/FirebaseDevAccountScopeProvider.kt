package com.haebi.petworld.auth

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import com.haebi.petworld.data.account.LocalAccountScope
import com.haebi.petworld.data.account.LocalAccountScopeResolver

class FirebaseDevAccountScopeProvider : ContentProvider() {
    override fun onCreate(): Boolean {
        LocalAccountScopeResolver.installFirebaseDev { appContext ->
            check(appContext.packageName == "com.haebi.petworld.dev") {
                "Unexpected Firebase account package."
            }
            val firebase = FirebaseApp.getApps(appContext)
                .firstOrNull { it.name == FirebaseApp.DEFAULT_APP_NAME }
                ?: error("Firebase development app is not initialized.")
            check(firebase.options.projectId == "petworld-dev") {
                "Unexpected Firebase project."
            }
            val firebaseUser = FirebaseAuth.getInstance(firebase).currentUser
            firebaseUser?.uid?.let(LocalAccountScope::fromFirebaseUid)
                ?: LocalAccountScope.Guest
        }
        return true
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? = null

    override fun getType(uri: Uri): String? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0
}
