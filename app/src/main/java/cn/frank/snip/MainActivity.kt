package cn.frank.snip

import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import cn.frank.snip.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        val cropLauncher =
            registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { data ->
                val uri = data?.data?.data ?: return@registerForActivityResult
                val bitmap = loadBitmap(uri, this)
                binding.image.setImageBitmap(bitmap)
            }
        binding.crop.setOnClickListener {
            val ratioWidth = binding.ratioWidth.text.toString().toFloatOrNull() ?: 0f
            val ratioHeight = binding.ratioHeight.text.toString().toFloatOrNull() ?: 0f
            val ratio = if (ratioWidth > 0f && ratioHeight > 0f) ratioWidth / ratioHeight else 0f
            cropLauncher.launch(CropActivity.getIntent(this, null, ratio))
        }
    }


}
