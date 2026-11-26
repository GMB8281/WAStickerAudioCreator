package com.marinov.wastickeraudiocreator.ui

import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.marinov.wastickeraudiocreator.R
import com.marinov.wastickeraudiocreator.stickers.PackRepository
import com.marinov.wastickeraudiocreator.whatsapp.WhatsAppManager
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var bottomNav: BottomNavigationView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        PackRepository.init(this)
        setContentView(R.layout.activity_main)

        findViewById<MaterialToolbar>(R.id.toolbar).title = getString(R.string.app_name)
        bottomNav = findViewById(R.id.bottom_nav)

        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.fragment_container, PacksFragment())
                .commit()
        }

        bottomNav.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_packs -> {
                    if (supportFragmentManager.findFragmentById(R.id.fragment_container) !is PacksFragment) {
                        supportFragmentManager.beginTransaction()
                            .replace(R.id.fragment_container, PacksFragment())
                            .commit()
                    }
                    true
                }
                R.id.nav_create -> {
                    startActivity(Intent(this, CreatePackActivity::class.java))
                    false
                }
                else -> false
            }
        }

        maybeRequestRoot()
    }

    private fun maybeRequestRoot() {
        val prefs = getSharedPreferences("app", MODE_PRIVATE)
        if (prefs.getBoolean("root_granted", false)) return
        showRootDialog(prefs)
    }

    private fun showRootDialog(prefs: SharedPreferences) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.root_required_title)
            .setMessage(R.string.root_required_message)
            .setCancelable(false)
            .setPositiveButton(R.string.grant_root) { _, _ ->
                lifecycleScope.launch {
                    val r = WhatsAppManager.requestRoot()
                    if (r.ok) {
                        prefs.edit().putBoolean("root_granted", true).apply()
                        toast(R.string.root_ok)
                    } else {
                        toast("${getString(R.string.root_failed)}: ${r.error}")
                        showRootDialog(prefs)
                    }
                }
            }
            .setNegativeButton(R.string.cancel) { _, _ -> finish() }
            .show()
    }
}