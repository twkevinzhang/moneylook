package tw.kevinzhang.extension_runtime.captcha

import com.google.gson.Gson
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Test

class FastApiCaptchaSolverTest {
    @Test
    fun `parses ddddocr API response`() {
        val solver = FastApiCaptchaSolver(OkHttpClient(), "https://ocr.invalid/ocr", Gson())

        val result = solver.parseResponse("""{"code":200,"message":"ok","data":"A7B9"}""")

        assertEquals(CaptchaSolveResult.Success("A7B9"), result)
    }

    @Test
    fun `parses nested response and rejects service error`() {
        val solver = FastApiCaptchaSolver(OkHttpClient(), "https://ocr.invalid/ocr", Gson())

        assertEquals(
            CaptchaSolveResult.Success("1234"),
            solver.parseResponse("""{"code":0,"message":"ok","data":{"text":"1234"}}"""),
        )
        assertEquals(
            CaptchaSolveResult.Error("bad image"),
            solver.parseResponse("""{"code":400,"message":"bad image","data":null}"""),
        )
    }

    @Test
    fun `rejects non HTTPS endpoint`() {
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            FastApiCaptchaSolver(OkHttpClient(), "http://ocr.invalid/ocr", Gson())
        }
    }
}
