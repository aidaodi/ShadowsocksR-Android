package com.bige0.shadowsocksr

import android.app.TaskStackBuilder
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.bige0.shadowsocksr.utils.*
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanIntentResult
import com.journeyapps.barcodescanner.ScanOptions

class ScannerActivity : AppCompatActivity()
{
	private fun navigateUp()
	{
		val intent = parentActivityIntent
		if (shouldUpRecreateTask(intent) || isTaskRoot)
		{
			TaskStackBuilder.create(this)
				.addNextIntentWithParentStack(intent)
				.startActivities()
		}
		else
		{
			finish()
		}
	}

	private val barcodeLauncher = registerForActivityResult(ScanContract()) { result: ScanIntentResult ->
		if (result.contents == null)
		{
			// 用户取消扫码
			navigateUp()
		}
		else
		{
			handleScanResult(result.contents)
		}
	}

	override fun onCreate(savedInstanceState: Bundle?)
	{
		super.onCreate(savedInstanceState)
		// 直接启动 journeyapps 内置扫码界面(现代 UI,自动处理相机权限)
		val options = ScanOptions()
		options.setDesiredBarcodeFormats(ScanOptions.QR_CODE)
		options.setPrompt(getString(R.string.add_profile_methods_scan_qr_code))
		options.setBeepEnabled(true)
		options.setOrientationLocked(false)
		barcodeLauncher.launch(options)
	}

	private fun handleScanResult(uri: String)
	{
		if (uri.isNotEmpty())
		{
			val all = Parser.findAllSs(uri)
			if (all.isNotEmpty())
			{
				for (p in all)
				{
					ShadowsocksApplication.app.profileManager.createProfile(p)
				}
			}

			val allSSR = Parser.findAllSsr(uri)
			if (allSSR.isNotEmpty())
			{
				for (p in allSSR)
				{
					ShadowsocksApplication.app.profileManager.createProfile(p)
				}
			}
		}
		navigateUp()
	}
}
