package com.alad1nks.jaiqal.core.session

import com.alad1nks.jaiqal.core.network.AccessTokenProvider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

sealed interface SessionState { data object Restoring:SessionState; data object SignedOut:SessionState; data class SignedIn(val userId:String):SessionState }
/** Firebase owns renewal; the application never issues or persists a refresh token. */
interface FirebaseIdentityProvider { suspend fun restore():FirebaseSession?; suspend fun signIn(email:String,password:String):FirebaseSession; suspend fun signUp(email:String,password:String):FirebaseSession; suspend fun freshIdToken():String; suspend fun signOut() }
data class FirebaseSession(val uid:String,val idToken:String)
class SessionManager(private val firebase:FirebaseIdentityProvider):AccessTokenProvider {
    private val mutex=Mutex(); private var token:String?=null
    private val mutable=MutableStateFlow<SessionState>(SessionState.Restoring); val state:StateFlow<SessionState> = mutable.asStateFlow()
    override fun accessToken()=token
    suspend fun restore() { val session=runCatching { firebase.restore() }.getOrNull(); apply(session) }
    suspend fun signIn(email:String,password:String)=apply(firebase.signIn(email,password))
    suspend fun signUp(email:String,password:String)=apply(firebase.signUp(email,password))
    suspend fun refresh():String = mutex.withLock { firebase.freshIdToken().also { token=it } }
    suspend fun logout(clearCache:suspend()->Unit) { firebase.signOut(); token=null; clearCache(); mutable.value=SessionState.SignedOut }
    private fun apply(session:FirebaseSession?) { token=session?.idToken; mutable.value=session?.let { SessionState.SignedIn(it.uid) }?:SessionState.SignedOut }
}
fun validEmail(value:String)=value.contains('@') && value.substringAfter('@').contains('.')
fun validPassword(value:String)=value.length>=8
