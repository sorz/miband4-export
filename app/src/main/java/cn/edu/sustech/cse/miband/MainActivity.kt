package cn.edu.sustech.cse.miband

import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import androidx.fragment.app.Fragment
import com.google.android.material.snackbar.Snackbar

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
    }
}

fun Fragment.showSnack(text: String) {
    Snackbar.make(requireView(), text, Snackbar.LENGTH_LONG).show()
}
