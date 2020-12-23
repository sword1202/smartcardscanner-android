/*
 * Copyright (C) 2020 Newlogic Pte. Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 *
 *
 */
package org.idpass.smartscanner.result

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import org.idpass.lite.Card
import org.idpass.lite.IDPassReader
import org.idpass.lite.exceptions.CardVerificationException
import org.idpass.lite.exceptions.InvalidCardException
import org.idpass.lite.exceptions.InvalidKeyException
import org.idpass.smartscanner.R
import org.idpass.smartscanner.lib.ScannerConstants
import org.idpass.smartscanner.lib.SmartScannerActivity
import org.idpass.smartscanner.lib.extension.empty
import org.idpass.smartscanner.lib.extension.hideKeyboard
import org.idpass.smartscanner.lib.utils.DateUtils.formatDate
import org.idpass.smartscanner.lib.utils.DateUtils.isValidDate

class IDPassResultActivity : AppCompatActivity(), View.OnClickListener {

    companion object {
        const val RESULT = "IDPASS_RESULT"
        private var idPassReader = IDPassReader()
    }

    private var pinCode: String = ""
    private var qrBytes:ByteArray? = null
    private var resultString : String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_idpass_result)
        overridePendingTransition(R.anim.slide_in_up, android.R.anim.fade_out)
        // Initialize UI
        val toolbar : Toolbar? = findViewById(R.id.toolbar)
        val pinCodeBtn: Button = findViewById(R.id.pinCodeAuth)
        pinCodeBtn.setOnClickListener(this)
        // Setup toolbar
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayShowTitleEnabled(false)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.setHomeButtonEnabled(true)
        supportActionBar?.setHomeAsUpIndicator(R.drawable.ic_close)
        // Display ID PASS Lite Result
        intent.getByteArrayExtra(RESULT)?.let {
            displayResult(it)
        } ?: run {
            intent.getBundleExtra(ResultActivity.RESULT)?.let {
                displayResult(it.getByteArray(ScannerConstants.IDPASS_LITE_RAW))
            }
        }
    }

    private fun displayResult(qrbytes: ByteArray? = null) {
        val tv =  (findViewById<TextView>(R.id.hex))
        val qrstr = qrbytes?.let { readCard(idPassReader, it) }
        tv.text = "\n" + qrstr + "\n"
        resultString = getShareResult(qrstr)
    }

    private fun getShareResult(result: String?) : String {
        val dump = StringBuilder()
        dump.append("Scan Result via ID PASS SmartScanner:\n\n")
        dump.append("ID PASS Lite\n")
        dump.append("-------------------------\n")
        dump.append(result)
        return dump.toString()
    }

    private fun readCard(idPassReader: IDPassReader, qrbytes: ByteArray, charsPerLine: Int = 33): String {
        if (charsPerLine < 4 || qrbytes.isEmpty()) {
            return ""
        }
        val dump = StringBuilder()
        var authStatus = "NO"
        var certStatus = ""
        var card: Card?
        try {
            try {
                card = idPassReader.open(qrbytes)
                certStatus = if (card.verifyCertificate()) "Verified" else "No certificate"
            } catch (ice: InvalidCardException) {
                card = idPassReader.open(qrbytes, true)
                certStatus = if (card.verifyCertificate()) "Not Verified" else "No certificate"
            }
            if (card != null) {
                Log.d(SmartScannerActivity.TAG, "card $card")
                if (pinCode.isNotEmpty()) {
                    try {
                        card.authenticateWithPIN(pinCode)
                        authStatus = "YES"
                        Toast.makeText(applicationContext, "Authentication Success", Toast.LENGTH_SHORT).show()
                    } catch (ve: CardVerificationException) {
                        Toast.makeText(applicationContext, "Authentication Fail", Toast.LENGTH_SHORT).show()
                    }
                }
                val fullName = card.getfullName()
                val givenName = card.givenName
                val surname = card.surname
                val dob = card.dateOfBirth
                val pob = card.placeOfBirth
                // TODO Display new fields in proper format
                // val gender = card.gender
                // val postalAdder = card.postalAddress
                // val UIN = card.uin

                if (fullName != null) {
                    dump.append("Full Name: $fullName\n")
                }
                if (givenName != null) {
                    dump.append("Given Names: $givenName\n")
                }
                if (surname != null) {
                    dump.append("Surname: $surname\n")
                }
                if (dob != null) {
                    val birthday = if (isValidDate(formatDate(dob))) formatDate(dob) else String.empty()
                    dump.append("Date of Birth: ${birthday}\n")
                }
                if (pob.isNotEmpty()) {
                    dump.append("Place of Birth: $pob\n")
                }
                dump.append("\n-------------------------\n\n")
                for ((key, value) in card.cardExtras) {
                    // typo workaround for extras value
                    val newKey = key.replace(" Of", " of")
                    dump.append("$newKey: $value\n")
                }
                dump.append("\n-------------------------\n\n")
                dump.append("Authenticated: $authStatus\n")
                dump.append("Certificate: $certStatus\n")
                qrBytes = qrbytes.clone()
                return dump.toString()
            } else {
                return "Error: Invalid IDPASS CARD"
            }
        } catch (ike: InvalidKeyException) {
            return "Error: Reader keyset is not authorized"
        } catch (e: Exception) {
            Log.d(SmartScannerActivity.Companion.TAG, "ID PASS exception: ${e.localizedMessage}")
            return "Error: SmartScanner cannot read IDPASS CARD"
        }
    }

    override fun onClick(v: View?) {
        when (v?.id) {
            R.id.pinCodeAuth -> {
                val cardpincode = findViewById<EditText>(R.id.cardPinCode)
                pinCode = cardpincode.text.toString()
                displayResult(qrBytes)
                hideKeyboard(cardpincode)
            }
        }
    }

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menuInflater.inflate(R.menu.share_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            android.R.id.home -> finish()
            R.id.share -> {
                resultString?.let {
                    val sendIntent: Intent = Intent().apply {
                        action = Intent.ACTION_SEND
                        putExtra(Intent.EXTRA_TEXT, it)
                        type = "text/plain"
                    }
                    val shareIntent = Intent.createChooser(sendIntent, null)
                    startActivity(shareIntent)
                }
            }
        }
        return super.onOptionsItemSelected(item)
    }
}