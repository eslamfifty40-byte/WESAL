package com.wesal.messenger.data.repository

import com.google.firebase.appcheck.FirebaseAppCheck
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Source
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.net.HttpURLConnection
import java.net.URL

/** Runs four quick checks when a send is not confirmed, so the banner says WHY (no secrets, no tokens printed). */
object NetProbe {
    suspend fun run(firestore: FirebaseFirestore, auth: FirebaseAuth): String = coroutineScope {
        val net = async(Dispatchers.IO) {
            runCatching {
                val c = URL("https://firestore.googleapis.com/").openConnection() as HttpURLConnection
                c.connectTimeout = 6000; c.readTimeout = 6000
                val code = c.responseCode
                c.disconnect()
                "net=$code"
            }.getOrElse { "net=ERR:${it.javaClass.simpleName}" }
        }
        val appCheck = async {
            withTimeoutOrNull(7000L) {
                runCatching {
                    FirebaseAppCheck.getInstance().getToken(false).await()
                    "appcheck=OK"
                }.getOrElse { "appcheck=ERR:${it.javaClass.simpleName}:${it.message?.take(60)}" }
            } ?: "appcheck=TIMEOUT"
        }
        val authTok = async {
            withTimeoutOrNull(7000L) {
                runCatching {
                    auth.currentUser?.getIdToken(false)?.await()
                    "auth=OK"
                }.getOrElse { "auth=ERR:${it.javaClass.simpleName}" }
            } ?: "auth=TIMEOUT"
        }
        val server = async {
            val uid = auth.currentUser?.uid ?: return@async "srv=NOUSER"
            withTimeoutOrNull(9000L) {
                runCatching {
                    firestore.collection("users").document(uid).get(Source.SERVER).await()
                    "srv=OK"
                }.getOrElse { e ->
                    val code = (e as? com.google.firebase.firestore.FirebaseFirestoreException)?.code
                    "srv=ERR:${code ?: e.javaClass.simpleName}"
                }
            } ?: "srv=TIMEOUT"
        }
                val write = async {
            val uid = auth.currentUser?.uid ?: return@async "wr=NOUSER"
            val t0 = System.currentTimeMillis()
            withTimeoutOrNull(12000L) {
                runCatching {
                    firestore.collection("users").document(uid)
                        .update("lastActivityPing", System.currentTimeMillis()).await()
                    "wr=OK(${System.currentTimeMillis() - t0}ms)"
                }.getOrElse { e ->
                    val code = (e as? com.google.firebase.firestore.FirebaseFirestoreException)?.code
                    "wr=ERR:${code ?: e.javaClass.simpleName}"
                }
            } ?: "wr=TIMEOUT"
        }
        val parts = listOf(net.await(), appCheck.await(), authTok.await(), server.await(), write.await())
        parts.joinToString(" ")
    }
}
