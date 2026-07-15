package ng1ok.yggdrasilmanager.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class YggdrasilThemeTest {
    @Test
    fun lightThemeUsesApprovedSemanticColors() {
        val scheme = yggdrasilColorScheme(darkTheme = false)
        assertEquals(Color(0xFF006C4C), scheme.primary)
        assertEquals(Color(0xFF53606F), scheme.tertiary)
        assertEquals(Color(0xFFBA1A1A), scheme.error)
        assertNotEquals(scheme.primary, scheme.error)
    }

    @Test
    fun darkThemeAndShapesUseApprovedTokens() {
        val scheme = yggdrasilColorScheme(darkTheme = true)
        assertEquals(Color(0xFF6DDBAD), scheme.primary)
        assertEquals(Color(0xFF101416), scheme.background)
        assertEquals(RoundedCornerShape(8.dp), YggdrasilShapes.medium)
        assertEquals(RoundedCornerShape(8.dp), YggdrasilShapes.large)
    }
}
