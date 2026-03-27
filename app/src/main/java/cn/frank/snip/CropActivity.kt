package cn.frank.snip

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import cn.frank.snip.databinding.FragmentContainerBinding

/**
 * 裁剪 Activity
 *
 * @author shangmingchao
 */
class CropActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(FragmentContainerBinding.inflate(layoutInflater).root)
        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.fragment_container, CropFragment.newInstance(
                    intent.getParcelableExtra(CropFragment.ARG_URI),
                    intent.getFloatExtra(CropFragment.CROP_RATIO, 0f)
                ))
                .commit()
        }
    }

    companion object {

        /**
         * 获取 Intent， [uri] 为空将打开系统相册选择图片
         */
        fun getIntent(context: Context, uri: Uri?, ratio: Float): Intent {
            val intent = Intent(context, CropActivity::class.java)
            intent.putExtra(CropFragment.ARG_URI, uri)
            intent.putExtra(CropFragment.CROP_RATIO, ratio)
            return intent
        }
    }
}
