package cn.frank.snip

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import cn.frank.snip.databinding.FragmentCropBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/**
 * 裁剪 Fragment
 *
 * @author shangmingchao
 */
class CropFragment : Fragment() {

    private var binding: FragmentCropBinding? = null
    private var ratio: Float = 0f

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        binding = FragmentCropBinding.inflate(inflater, container, false)
        return binding?.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val uri = arguments?.getParcelable<Uri>(ARG_URI)
        ratio = arguments?.getFloat(CROP_RATIO) ?: 0f
        if (uri != null) {
            loadImage(uri)
        } else {
            registerForActivityResult(
                ActivityResultContracts.GetContent(),
                ::loadImage
            ).launch("image/*")
        }
        binding?.close?.setOnClickListener {
            requireActivity().finish()
        }
        binding?.rotate?.setOnClickListener {
            binding?.cropImageView?.rotate()
        }
        binding?.confirm?.setOnClickListener {
            crop()
        }
        binding?.reset?.setOnClickListener {
            binding?.cropImageView?.reset()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        binding = null
    }

    private fun loadImage(uri: Uri) {
        val bitmap = loadBitmap(uri, requireContext())
        binding?.cropImageView?.setCropRatio(ratio)
        binding?.cropImageView?.setImageBitmap(bitmap)
    }

    private fun crop() {
        val croppedBitmap = binding?.cropImageView?.getCroppedImage() ?: return
        binding?.confirm?.isEnabled = false
        viewLifecycleOwner.lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                val file = File(requireContext().cacheDir, "cropped.jpg")
                if (file.exists()) {
                    file.delete()
                }
                croppedBitmap.compress(Bitmap.CompressFormat.JPEG, 100, FileOutputStream(file))
                val intent = Intent()
                intent.setDataAndType(Uri.fromFile(file), "image/jpeg")
                requireActivity().setResult(Activity.RESULT_OK, intent)
                requireActivity().finish()
            }
        }
    }

    companion object {

        /**
         * 图片路径
         */
        const val ARG_URI = "uri"

        /**
         * 裁剪比例
         */
        const val CROP_RATIO = "crop_ratio"

        /**
         * 创建实例
         */
        fun newInstance(uri: Uri?, ratio: Float): CropFragment {
            val fragment = CropFragment()
            val args = Bundle()
            args.putParcelable(ARG_URI, uri)
            args.putFloat(CROP_RATIO, ratio)
            fragment.arguments = args
            return fragment
        }
    }
}
