package com.driver.app

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.Spinner
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class LoginActivity : AppCompatActivity() {

    companion object {
        private const val PREFS = "yanpro_settings"
        private const val KEY_LOGIN = "driver_login"
        private const val KEY_ROLE = "driver_role"
        private const val KEY_NAME = "driver_name"
        private const val DEFAULT_LOGIN = "admin"
        private const val DEFAULT_PASS = "12345"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_login)

        val etLogin = findViewById<EditText>(R.id.etLogin)
        val etPassword = findViewById<EditText>(R.id.etPassword)
        val spinnerRole = findViewById<Spinner>(R.id.spinnerRole)
        val btnLogin = findViewById<Button>(R.id.btnLogin)

        val roleOptions = arrayOf("Водитель", "Мастер")
        val roleValues = arrayOf("driver", "mechanic")
        val roleAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, roleOptions)
        roleAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerRole.adapter = roleAdapter

        btnLogin.setOnClickListener {
            val login = etLogin.text.toString().trim()
            val pass = etPassword.text.toString().trim()
            val role = roleValues[spinnerRole.selectedItemPosition]

            if (login.isEmpty() || pass.isEmpty()) {
                Toast.makeText(this, "Заполните логин и пароль", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            if (login == DEFAULT_LOGIN && pass == DEFAULT_PASS) {
                saveAndStart(login, role)
            } else {
                Toast.makeText(this, "Неверный логин или пароль", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun saveAndStart(login: String, role: String) {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
            .putString(KEY_LOGIN, login)
            .putString(KEY_ROLE, role)
            .putString(KEY_NAME, if (role == "mechanic") "Мастер" else "Водитель")
            .apply()

        val intent = Intent(this, MainActivity::class.java)
        intent.putExtra("driver_role", role)
        intent.putExtra("driver_name", if (role == "mechanic") "Мастер" else "Водитель")
        startActivity(intent)
        finish()
    }
}
