package com.newlogic.mlkit.demo

import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Paint
import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.View.GONE
import android.view.View.VISIBLE
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.gson.JsonParser
import com.newlogic.mlkit.R
import com.newlogic.mlkit.demo.utils.AnimationUtils
import com.newlogic.mlkitlib.newlogic.MLKitActivity
import com.newlogic.mlkitlib.newlogic.config.Config
import com.newlogic.mlkitlib.newlogic.config.Modes
import com.newlogic.mlkitlib.newlogic.extension.empty
import kotlinx.android.synthetic.main.activity_main.*


class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
    }

    public override fun onActivityResult(requestCode: Int, resultCode: Int, intent: Intent?) {
        super.onActivityResult(requestCode, resultCode, intent)
        if (requestCode == OP_MLKIT) {
            Log.d(TAG, "Plugin post ML Activity resultCode $resultCode")
            if (resultCode == RESULT_OK) {
                val returnedResult = intent?.getStringExtra(MLKitActivity.MLKIT_RESULT)
                val originalHeight = 750 // Approx. 250dp for image and textview
                returnedResult?.let {
                    val result = JsonParser.parseString(it).asJsonObject
                    if (result["imagePath"] != null) {
                        val path = result["imagePath"].asString
                        val myBitmap = BitmapFactory.decodeFile(path)
                        imageView.setImageBitmap(myBitmap)
                        txtImgAction.visibility = VISIBLE
                        txtImgAction.paintFlags = txtImgAction.paintFlags or Paint.UNDERLINE_TEXT_FLAG
                        txtImgAction.setOnClickListener {
                            AnimationUtils.expandCollapse(imageView, originalHeight)
                            txtImgAction.text = if (imageView.visibility == GONE) getString(R.string.action_hide) else getString(R.string.action_show)
                        }
                    }
                    editTextTextMultiLine.setText(it)
                    txtRawDataAction.visibility = VISIBLE
                    txtRawDataAction.paintFlags = txtRawDataAction.paintFlags or Paint.UNDERLINE_TEXT_FLAG
                    txtRawDataAction.setOnClickListener {
                        AnimationUtils.expandCollapse(editTextTextMultiLine, originalHeight)
                        txtRawDataAction.text = if (editTextTextMultiLine.visibility == GONE) getString(R.string.action_hide) else getString(R.string.action_show)
                    }
                }
            }
        }
    }

    fun startScanningActivity(view: View) {
        val intent = Intent(this, MLKitActivity::class.java)
        intent.putExtra(MLKitActivity.MLKIT_CONFIG, sampleConfig(Modes.MRZ.value))
        startActivityForResult(intent, OP_MLKIT)
    }

    fun startPDF417ScanningActivity(view: View) {
        val intent = Intent(this, MLKitActivity::class.java)
        intent.putExtra(MODE, "pdf417")
        startActivityForResult(intent, OP_MLKIT)
    }

    fun startQRCodeScanningActivity(view: View) {
        // TODO add QR implementation Modes.QR_CODE
        Toast.makeText(this, "Not yet available!", Toast.LENGTH_LONG).show()
    }

    fun startBarcodeScanningActivity(view: View) {
        val intent = Intent(this, MLKitActivity::class.java)
        intent.putExtra(MLKitActivity.MLKIT_CONFIG, sampleConfig(Modes.BARCODE.value))
        startActivityForResult(intent, OP_MLKIT)
    }

    private fun sampleConfig(mode: String) = Config(
        font = String.empty(),
        language = String.empty(),
        label = String.empty(),
        mode = mode,
        true
    )

    companion object {
        private const val TAG = "Newlogic-MLkit"
        private const val OP_MLKIT = 1001
        private const val MODE = "mode"
    }
}