package com.sirrlevi.guardian.parent.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.sirrlevi.guardian.parent.ParentViewModel

/** First screen: sign in, or create the family account. */
@Composable
fun AuthScreen(vm: ParentViewModel) {
    var mode by remember { mutableIntStateOf(0) }
    var email by remember { mutableStateOf(vm.email) }
    var password by remember { mutableStateOf("") }
    val create = mode == 1
    val canSubmit = !vm.authBusy && email.contains("@") && password.length >= 6

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).systemBarsPadding().imePadding()) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            GuardianLogo(124.dp)
            Spacer(Modifier.height(18.dp))
            Text("Guardian", style = MaterialTheme.typography.headlineLarge)
            Text("Calm, transparent screen-time control for your family.", style = MaterialTheme.typography.bodyMedium,
                color = gColors.muted, textAlign = TextAlign.Center)
            Spacer(Modifier.height(28.dp))

            GCard(padding = 18.dp) {
                Segmented(listOf("Sign in", "Create account"), mode, { mode = it; vm.authError = null })
                Spacer(Modifier.height(16.dp))
                OutlinedTextField(
                    value = email, onValueChange = { email = it }, singleLine = true, label = { Text("Email") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next),
                    modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp)
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = password, onValueChange = { password = it }, singleLine = true, label = { Text("Password") },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                    supportingText = { if (create) Text("At least 6 characters") },
                    modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp)
                )
                val error = vm.authError
                if (error != null) {
                    Spacer(Modifier.height(10.dp))
                    Text(error, style = MaterialTheme.typography.bodySmall, color = gColors.danger)
                }
                Spacer(Modifier.height(16.dp))
                if (vm.authBusy) {
                    Box(Modifier.fillMaxWidth().height(48.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(Modifier.size(26.dp), strokeWidth = 3.dp)
                    }
                } else {
                    PrimaryButton(if (create) "Create account" else "Sign in", { vm.signIn(create, email, password) }, Modifier.fillMaxWidth(), enabled = canSubmit)
                }
            }
            Spacer(Modifier.height(18.dp))
            Text(
                if (create) "This is the parent account. You'll connect your child's phone with a one-time code after signing in."
                else "New here? Choose Create account. Your child's phone connects later with a one-time code.",
                style = MaterialTheme.typography.bodySmall, color = gColors.muted, textAlign = TextAlign.Center
            )
        }
    }
}
