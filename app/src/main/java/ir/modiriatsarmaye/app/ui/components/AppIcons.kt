package ir.modiriatsarmaye.app.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * Lightweight in-app Material icon definitions replacing the heavy material-icons-extended
 * dependency (22 MB DEX) so release bundling stays compact and fast.
 */
object AppIcons {
    private fun buildIcon(
        name: String,
        autoMirror: Boolean = false,
        block: androidx.compose.ui.graphics.vector.PathBuilder.() -> Unit,
    ): ImageVector = ImageVector.Builder(
        name = name,
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
        autoMirror = autoMirror,
    ).path(fill = SolidColor(Color.Black), pathBuilder = block).build()

    val ArrowForwardIos: ImageVector by lazy {
        buildIcon("ArrowForwardIos", autoMirror = true) {
            moveTo(6.23f, 20.23f)
            lineToRelative(1.77f, 1.77f)
            lineToRelative(10.0f, -10.0f)
            lineToRelative(-10.0f, -10.0f)
            lineToRelative(-1.77f, 1.77f)
            lineToRelative(8.23f, 8.23f)
            close()
        }
    }

    val ReceiptLong: ImageVector by lazy {
        buildIcon("ReceiptLong", autoMirror = true) {
            moveTo(19.5f, 3.5f)
            lineTo(18.0f, 2.0f)
            lineToRelative(-1.5f, 1.5f)
            lineTo(15.0f, 2.0f)
            lineToRelative(-1.5f, 1.5f)
            lineTo(12.0f, 2.0f)
            lineToRelative(-1.5f, 1.5f)
            lineTo(9.0f, 2.0f)
            lineTo(7.5f, 3.5f)
            lineTo(6.0f, 2.0f)
            verticalLineToRelative(14.0f)
            horizontalLineTo(3.0f)
            verticalLineToRelative(3.0f)
            curveToRelative(0.0f, 1.66f, 1.34f, 3.0f, 3.0f, 3.0f)
            horizontalLineToRelative(12.0f)
            curveToRelative(1.66f, 0.0f, 3.0f, -1.34f, 3.0f, -3.0f)
            verticalLineTo(2.0f)
            lineTo(19.5f, 3.5f)
            close()
            moveTo(19.0f, 19.0f)
            curveToRelative(0.0f, 0.55f, -0.45f, 1.0f, -1.0f, 1.0f)
            reflectiveCurveToRelative(-1.0f, -0.45f, -1.0f, -1.0f)
            verticalLineToRelative(-3.0f)
            horizontalLineTo(8.0f)
            verticalLineTo(5.0f)
            horizontalLineToRelative(11.0f)
            verticalLineTo(19.0f)
            close()
        }
    }

    val ReceiptLongOutlined: ImageVector by lazy {
        buildIcon("ReceiptLongOutlined", autoMirror = true) {
            moveTo(19.5f, 3.5f)
            lineTo(18.0f, 2.0f)
            lineToRelative(-1.5f, 1.5f)
            lineTo(15.0f, 2.0f)
            lineToRelative(-1.5f, 1.5f)
            lineTo(12.0f, 2.0f)
            lineToRelative(-1.5f, 1.5f)
            lineTo(9.0f, 2.0f)
            lineTo(7.5f, 3.5f)
            lineTo(6.0f, 2.0f)
            verticalLineToRelative(14.0f)
            horizontalLineTo(3.0f)
            verticalLineToRelative(3.0f)
            curveToRelative(0.0f, 1.66f, 1.34f, 3.0f, 3.0f, 3.0f)
            horizontalLineToRelative(12.0f)
            curveToRelative(1.66f, 0.0f, 3.0f, -1.34f, 3.0f, -3.0f)
            verticalLineTo(2.0f)
            lineTo(19.5f, 3.5f)
            close()
            moveTo(15.0f, 20.0f)
            horizontalLineTo(6.0f)
            curveToRelative(-0.55f, 0.0f, -1.0f, -0.45f, -1.0f, -1.0f)
            verticalLineToRelative(-1.0f)
            horizontalLineToRelative(10.0f)
            verticalLineTo(20.0f)
            close()
            moveTo(19.0f, 19.0f)
            curveToRelative(0.0f, 0.55f, -0.45f, 1.0f, -1.0f, 1.0f)
            reflectiveCurveToRelative(-1.0f, -0.45f, -1.0f, -1.0f)
            verticalLineToRelative(-3.0f)
            horizontalLineTo(8.0f)
            verticalLineTo(5.0f)
            horizontalLineToRelative(11.0f)
            verticalLineTo(19.0f)
            close()
        }
    }

    val AccountBalance: ImageVector by lazy {
        buildIcon("AccountBalance") {
            moveTo(4.0f, 10.0f)
            horizontalLineToRelative(3.0f)
            verticalLineToRelative(7.0f)
            horizontalLineToRelative(-3.0f)
            close()
        }
    }

    val Analytics: ImageVector by lazy {
        buildIcon("Analytics") {
            moveTo(19.0f, 3.0f)
            lineTo(5.0f, 3.0f)
            curveToRelative(-1.1f, 0.0f, -2.0f, 0.9f, -2.0f, 2.0f)
            verticalLineToRelative(14.0f)
            curveToRelative(0.0f, 1.1f, 0.9f, 2.0f, 2.0f, 2.0f)
            horizontalLineToRelative(14.0f)
            curveToRelative(1.1f, 0.0f, 2.0f, -0.9f, 2.0f, -2.0f)
            lineTo(21.0f, 5.0f)
            curveToRelative(0.0f, -1.1f, -0.9f, -2.0f, -2.0f, -2.0f)
            close()
            moveTo(9.0f, 17.0f)
            lineTo(7.0f, 17.0f)
            verticalLineToRelative(-5.0f)
            horizontalLineToRelative(2.0f)
            verticalLineToRelative(5.0f)
            close()
            moveTo(13.0f, 17.0f)
            horizontalLineToRelative(-2.0f)
            verticalLineToRelative(-3.0f)
            horizontalLineToRelative(2.0f)
            verticalLineToRelative(3.0f)
            close()
            moveTo(13.0f, 12.0f)
            horizontalLineToRelative(-2.0f)
            verticalLineToRelative(-2.0f)
            horizontalLineToRelative(2.0f)
            verticalLineToRelative(2.0f)
            close()
            moveTo(17.0f, 17.0f)
            horizontalLineToRelative(-2.0f)
            lineTo(15.0f, 7.0f)
            horizontalLineToRelative(2.0f)
            verticalLineToRelative(10.0f)
            close()
        }
    }

    val CalendarToday: ImageVector by lazy {
        buildIcon("CalendarToday") {
            moveTo(20.0f, 3.0f)
            horizontalLineToRelative(-1.0f)
            lineTo(19.0f, 1.0f)
            horizontalLineToRelative(-2.0f)
            verticalLineToRelative(2.0f)
            lineTo(7.0f, 3.0f)
            lineTo(7.0f, 1.0f)
            lineTo(5.0f, 1.0f)
            verticalLineToRelative(2.0f)
            lineTo(4.0f, 3.0f)
            curveToRelative(-1.1f, 0.0f, -2.0f, 0.9f, -2.0f, 2.0f)
            verticalLineToRelative(16.0f)
            curveToRelative(0.0f, 1.1f, 0.9f, 2.0f, 2.0f, 2.0f)
            horizontalLineToRelative(16.0f)
            curveToRelative(1.1f, 0.0f, 2.0f, -0.9f, 2.0f, -2.0f)
            lineTo(22.0f, 5.0f)
            curveToRelative(0.0f, -1.1f, -0.9f, -2.0f, -2.0f, -2.0f)
            close()
            moveTo(20.0f, 21.0f)
            lineTo(4.0f, 21.0f)
            lineTo(4.0f, 8.0f)
            horizontalLineToRelative(16.0f)
            verticalLineToRelative(13.0f)
            close()
        }
    }

    val DeleteOutline: ImageVector by lazy {
        buildIcon("DeleteOutline") {
            moveTo(6.0f, 19.0f)
            curveToRelative(0.0f, 1.1f, 0.9f, 2.0f, 2.0f, 2.0f)
            horizontalLineToRelative(8.0f)
            curveToRelative(1.1f, 0.0f, 2.0f, -0.9f, 2.0f, -2.0f)
            lineTo(18.0f, 7.0f)
            lineTo(6.0f, 7.0f)
            verticalLineToRelative(12.0f)
            close()
            moveTo(8.0f, 9.0f)
            horizontalLineToRelative(8.0f)
            verticalLineToRelative(10.0f)
            lineTo(8.0f, 19.0f)
            lineTo(8.0f, 9.0f)
            close()
            moveTo(15.5f, 4.0f)
            lineToRelative(-1.0f, -1.0f)
            horizontalLineToRelative(-5.0f)
            lineToRelative(-1.0f, 1.0f)
            lineTo(5.0f, 4.0f)
            verticalLineToRelative(2.0f)
            horizontalLineToRelative(14.0f)
            lineTo(19.0f, 4.0f)
            close()
        }
    }

    val EditCalendar: ImageVector by lazy {
        buildIcon("EditCalendar") {
            moveTo(12.0f, 22.0f)
            horizontalLineTo(5.0f)
            curveToRelative(-1.11f, 0.0f, -2.0f, -0.9f, -2.0f, -2.0f)
            lineTo(3.01f, 6.0f)
            curveToRelative(0.0f, -1.1f, 0.88f, -2.0f, 1.99f, -2.0f)
            horizontalLineToRelative(1.0f)
            verticalLineTo(2.0f)
            horizontalLineToRelative(2.0f)
            verticalLineToRelative(2.0f)
            horizontalLineToRelative(8.0f)
            verticalLineTo(2.0f)
            horizontalLineToRelative(2.0f)
            verticalLineToRelative(2.0f)
            horizontalLineToRelative(1.0f)
            curveToRelative(1.1f, 0.0f, 2.0f, 0.9f, 2.0f, 2.0f)
            verticalLineToRelative(6.0f)
            horizontalLineToRelative(-2.0f)
            verticalLineToRelative(-2.0f)
            horizontalLineTo(5.0f)
            verticalLineToRelative(10.0f)
            horizontalLineToRelative(7.0f)
            verticalLineTo(22.0f)
            close()
            moveTo(22.13f, 16.99f)
            lineToRelative(0.71f, -0.71f)
            curveToRelative(0.39f, -0.39f, 0.39f, -1.02f, 0.0f, -1.41f)
            lineToRelative(-0.71f, -0.71f)
            curveToRelative(-0.39f, -0.39f, -1.02f, -0.39f, -1.41f, 0.0f)
            lineToRelative(-0.71f, 0.71f)
            lineTo(22.13f, 16.99f)
            close()
            moveTo(21.42f, 17.7f)
            lineToRelative(-5.3f, 5.3f)
            horizontalLineTo(14.0f)
            verticalLineToRelative(-2.12f)
            lineToRelative(5.3f, -5.3f)
            lineTo(21.42f, 17.7f)
            close()
        }
    }

    val ExpandLess: ImageVector by lazy {
        buildIcon("ExpandLess") {
            moveTo(12.0f, 8.0f)
            lineToRelative(-6.0f, 6.0f)
            lineToRelative(1.41f, 1.41f)
            lineTo(12.0f, 10.83f)
            lineToRelative(4.59f, 4.58f)
            lineTo(18.0f, 14.0f)
            close()
        }
    }

    val ExpandMore: ImageVector by lazy {
        buildIcon("ExpandMore") {
            moveTo(16.59f, 8.59f)
            lineTo(12.0f, 13.17f)
            lineTo(7.41f, 8.59f)
            lineTo(6.0f, 10.0f)
            lineToRelative(6.0f, 6.0f)
            lineToRelative(6.0f, -6.0f)
            close()
        }
    }

    val FileDownload: ImageVector by lazy {
        buildIcon("FileDownload") {
            moveTo(19.0f, 9.0f)
            horizontalLineToRelative(-4.0f)
            verticalLineTo(3.0f)
            horizontalLineTo(9.0f)
            verticalLineToRelative(6.0f)
            horizontalLineTo(5.0f)
            lineToRelative(7.0f, 7.0f)
            lineToRelative(7.0f, -7.0f)
            close()
            moveTo(5.0f, 18.0f)
            verticalLineToRelative(2.0f)
            horizontalLineToRelative(14.0f)
            verticalLineToRelative(-2.0f)
            horizontalLineTo(5.0f)
            close()
        }
    }

    val FileUpload: ImageVector by lazy {
        buildIcon("FileUpload") {
            moveTo(9.0f, 16.0f)
            horizontalLineToRelative(6.0f)
            verticalLineToRelative(-6.0f)
            horizontalLineToRelative(4.0f)
            lineToRelative(-7.0f, -7.0f)
            lineToRelative(-7.0f, 7.0f)
            horizontalLineToRelative(4.0f)
            close()
            moveTo(5.0f, 18.0f)
            horizontalLineToRelative(14.0f)
            verticalLineToRelative(2.0f)
            lineTo(5.0f, 20.0f)
            close()
        }
    }

    val FolderZip: ImageVector by lazy {
        buildIcon("FolderZip") {
            moveTo(20.0f, 6.0f)
            horizontalLineToRelative(-8.0f)
            lineToRelative(-2.0f, -2.0f)
            horizontalLineTo(4.0f)
            curveTo(2.9f, 4.0f, 2.01f, 4.9f, 2.01f, 6.0f)
            lineTo(2.0f, 18.0f)
            curveToRelative(0.0f, 1.1f, 0.9f, 2.0f, 2.0f, 2.0f)
            horizontalLineToRelative(16.0f)
            curveToRelative(1.1f, 0.0f, 2.0f, -0.9f, 2.0f, -2.0f)
            verticalLineTo(8.0f)
            curveTo(22.0f, 6.9f, 21.1f, 6.0f, 20.0f, 6.0f)
            close()
            moveTo(18.0f, 12.0f)
            horizontalLineToRelative(-2.0f)
            verticalLineToRelative(2.0f)
            horizontalLineToRelative(2.0f)
            verticalLineToRelative(2.0f)
            horizontalLineToRelative(-2.0f)
            verticalLineToRelative(2.0f)
            horizontalLineToRelative(-2.0f)
            verticalLineToRelative(-2.0f)
            horizontalLineToRelative(2.0f)
            verticalLineToRelative(-2.0f)
            horizontalLineToRelative(-2.0f)
            verticalLineToRelative(-2.0f)
            horizontalLineToRelative(2.0f)
            verticalLineToRelative(-2.0f)
            horizontalLineToRelative(-2.0f)
            verticalLineTo(8.0f)
            horizontalLineToRelative(2.0f)
            verticalLineToRelative(2.0f)
            horizontalLineToRelative(2.0f)
            verticalLineTo(12.0f)
            close()
        }
    }

    val Inbox: ImageVector by lazy {
        buildIcon("Inbox") {
            moveTo(19.0f, 3.0f)
            lineTo(4.99f, 3.0f)
            curveToRelative(-1.11f, 0.0f, -1.98f, 0.89f, -1.98f, 2.0f)
            lineTo(3.0f, 19.0f)
            curveToRelative(0.0f, 1.1f, 0.88f, 2.0f, 1.99f, 2.0f)
            lineTo(19.0f, 21.0f)
            curveToRelative(1.1f, 0.0f, 2.0f, -0.9f, 2.0f, -2.0f)
            lineTo(21.0f, 5.0f)
            curveToRelative(0.0f, -1.11f, -0.9f, -2.0f, -2.0f, -2.0f)
            close()
            moveTo(19.0f, 15.0f)
            horizontalLineToRelative(-4.0f)
            curveToRelative(0.0f, 1.66f, -1.35f, 3.0f, -3.0f, 3.0f)
            reflectiveCurveToRelative(-3.0f, -1.34f, -3.0f, -3.0f)
            lineTo(4.99f, 15.0f)
            lineTo(4.99f, 5.0f)
            lineTo(19.0f, 5.0f)
            verticalLineToRelative(10.0f)
            close()
        }
    }

    val MenuBook: ImageVector by lazy {
        buildIcon("MenuBook") {
            moveTo(21.0f, 5.0f)
            curveToRelative(-1.11f, -0.35f, -2.33f, -0.5f, -3.5f, -0.5f)
            curveToRelative(-1.95f, 0.0f, -4.05f, 0.4f, -5.5f, 1.5f)
            curveToRelative(-1.45f, -1.1f, -3.55f, -1.5f, -5.5f, -1.5f)
            reflectiveCurveTo(2.45f, 4.9f, 1.0f, 6.0f)
            verticalLineToRelative(14.65f)
            curveToRelative(0.0f, 0.25f, 0.25f, 0.5f, 0.5f, 0.5f)
            curveToRelative(0.1f, 0.0f, 0.15f, -0.05f, 0.25f, -0.05f)
            curveTo(3.1f, 20.45f, 5.05f, 20.0f, 6.5f, 20.0f)
            curveToRelative(1.95f, 0.0f, 4.05f, 0.4f, 5.5f, 1.5f)
            curveToRelative(1.35f, -0.85f, 3.8f, -1.5f, 5.5f, -1.5f)
            curveToRelative(1.65f, 0.0f, 3.35f, 0.3f, 4.75f, 1.05f)
            curveToRelative(0.1f, 0.05f, 0.15f, 0.05f, 0.25f, 0.05f)
            curveToRelative(0.25f, 0.0f, 0.5f, -0.25f, 0.5f, -0.5f)
            verticalLineTo(6.0f)
            curveTo(22.4f, 5.55f, 21.75f, 5.25f, 21.0f, 5.0f)
            close()
            moveTo(21.0f, 18.5f)
            curveToRelative(-1.1f, -0.35f, -2.3f, -0.5f, -3.5f, -0.5f)
            curveToRelative(-1.7f, 0.0f, -4.15f, 0.65f, -5.5f, 1.5f)
            verticalLineTo(8.0f)
            curveToRelative(1.35f, -0.85f, 3.8f, -1.5f, 5.5f, -1.5f)
            curveToRelative(1.2f, 0.0f, 2.4f, 0.15f, 3.5f, 0.5f)
            verticalLineTo(18.5f)
            close()
        }
    }

    val MoreHoriz: ImageVector by lazy {
        buildIcon("MoreHoriz") {
            moveTo(6.0f, 10.0f)
            curveToRelative(-1.1f, 0.0f, -2.0f, 0.9f, -2.0f, 2.0f)
            reflectiveCurveToRelative(0.9f, 2.0f, 2.0f, 2.0f)
            reflectiveCurveToRelative(2.0f, -0.9f, 2.0f, -2.0f)
            reflectiveCurveToRelative(-0.9f, -2.0f, -2.0f, -2.0f)
            close()
            moveTo(18.0f, 10.0f)
            curveToRelative(-1.1f, 0.0f, -2.0f, 0.9f, -2.0f, 2.0f)
            reflectiveCurveToRelative(0.9f, 2.0f, 2.0f, 2.0f)
            reflectiveCurveToRelative(2.0f, -0.9f, 2.0f, -2.0f)
            reflectiveCurveToRelative(-0.9f, -2.0f, -2.0f, -2.0f)
            close()
            moveTo(12.0f, 10.0f)
            curveToRelative(-1.1f, 0.0f, -2.0f, 0.9f, -2.0f, 2.0f)
            reflectiveCurveToRelative(0.9f, 2.0f, 2.0f, 2.0f)
            reflectiveCurveToRelative(2.0f, -0.9f, 2.0f, -2.0f)
            reflectiveCurveToRelative(-0.9f, -2.0f, -2.0f, -2.0f)
            close()
        }
    }

    val PieChart: ImageVector by lazy {
        buildIcon("PieChart") {
            moveTo(11.0f, 2.0f)
            verticalLineToRelative(20.0f)
            curveToRelative(-5.07f, -0.5f, -9.0f, -4.79f, -9.0f, -10.0f)
            reflectiveCurveToRelative(3.93f, -9.5f, 9.0f, -10.0f)
            close()
            moveTo(13.03f, 2.0f)
            verticalLineToRelative(8.99f)
            lineTo(22.0f, 10.99f)
            curveToRelative(-0.47f, -4.74f, -4.24f, -8.52f, -8.97f, -8.99f)
            close()
            moveTo(13.03f, 13.01f)
            lineTo(13.03f, 22.0f)
            curveToRelative(4.74f, -0.47f, 8.5f, -4.25f, 8.97f, -8.99f)
            horizontalLineToRelative(-8.97f)
            close()
        }
    }

    val QueryStats: ImageVector by lazy {
        buildIcon("QueryStats") {
            moveTo(19.88f, 18.47f)
            curveToRelative(0.44f, -0.7f, 0.7f, -1.51f, 0.7f, -2.39f)
            curveToRelative(0.0f, -2.49f, -2.01f, -4.5f, -4.5f, -4.5f)
            reflectiveCurveToRelative(-4.5f, 2.01f, -4.5f, 4.5f)
            reflectiveCurveToRelative(2.01f, 4.5f, 4.49f, 4.5f)
            curveToRelative(0.88f, 0.0f, 1.7f, -0.26f, 2.39f, -0.7f)
            lineTo(21.58f, 23.0f)
            lineTo(23.0f, 21.58f)
            lineTo(19.88f, 18.47f)
            close()
            moveTo(16.08f, 18.58f)
            curveToRelative(-1.38f, 0.0f, -2.5f, -1.12f, -2.5f, -2.5f)
            curveToRelative(0.0f, -1.38f, 1.12f, -2.5f, 2.5f, -2.5f)
            reflectiveCurveToRelative(2.5f, 1.12f, 2.5f, 2.5f)
            curveTo(18.58f, 17.46f, 17.46f, 18.58f, 16.08f, 18.58f)
            close()
            moveTo(15.72f, 10.08f)
            curveToRelative(-0.74f, 0.02f, -1.45f, 0.18f, -2.1f, 0.45f)
            lineToRelative(-0.55f, -0.83f)
            lineToRelative(-3.8f, 6.18f)
            lineToRelative(-3.01f, -3.52f)
            lineToRelative(-3.63f, 5.81f)
            lineTo(1.0f, 17.0f)
            lineToRelative(5.0f, -8.0f)
            lineToRelative(3.0f, 3.5f)
            lineTo(13.0f, 6.0f)
            curveTo(13.0f, 6.0f, 15.72f, 10.08f, 15.72f, 10.08f)
            close()
            moveTo(18.31f, 10.58f)
            curveToRelative(-0.64f, -0.28f, -1.33f, -0.45f, -2.05f, -0.49f)
            curveToRelative(0.0f, 0.0f, 5.12f, -8.09f, 5.12f, -8.09f)
            lineTo(23.0f, 3.18f)
            lineTo(18.31f, 10.58f)
            close()
        }
    }

    val Savings: ImageVector by lazy {
        buildIcon("Savings") {
            moveTo(19.83f, 7.5f)
            lineToRelative(-2.27f, -2.27f)
            curveToRelative(0.07f, -0.42f, 0.18f, -0.81f, 0.32f, -1.15f)
            curveTo(17.96f, 3.9f, 18.0f, 3.71f, 18.0f, 3.5f)
            curveTo(18.0f, 2.67f, 17.33f, 2.0f, 16.5f, 2.0f)
            curveToRelative(-1.64f, 0.0f, -3.09f, 0.79f, -4.0f, 2.0f)
            lineToRelative(-5.0f, 0.0f)
            curveTo(4.46f, 4.0f, 2.0f, 6.46f, 2.0f, 9.5f)
            reflectiveCurveTo(4.5f, 21.0f, 4.5f, 21.0f)
            lineToRelative(5.5f, 0.0f)
            verticalLineToRelative(-2.0f)
            horizontalLineToRelative(2.0f)
            verticalLineToRelative(2.0f)
            lineToRelative(5.5f, 0.0f)
            lineToRelative(1.68f, -5.59f)
            lineTo(22.0f, 14.47f)
            verticalLineTo(7.5f)
            horizontalLineTo(19.83f)
            close()
            moveTo(13.0f, 9.0f)
            horizontalLineTo(8.0f)
            verticalLineTo(7.0f)
            horizontalLineToRelative(5.0f)
            verticalLineTo(9.0f)
            close()
            moveTo(16.0f, 11.0f)
            curveToRelative(-0.55f, 0.0f, -1.0f, -0.45f, -1.0f, -1.0f)
            curveToRelative(0.0f, -0.55f, 0.45f, -1.0f, 1.0f, -1.0f)
            reflectiveCurveToRelative(1.0f, 0.45f, 1.0f, 1.0f)
            curveTo(17.0f, 10.55f, 16.55f, 11.0f, 16.0f, 11.0f)
            close()
        }
    }

    val Sync: ImageVector by lazy {
        buildIcon("Sync") {
            moveTo(12.0f, 4.0f)
            lineTo(12.0f, 1.0f)
            lineTo(8.0f, 5.0f)
            lineToRelative(4.0f, 4.0f)
            lineTo(12.0f, 6.0f)
            curveToRelative(3.31f, 0.0f, 6.0f, 2.69f, 6.0f, 6.0f)
            curveToRelative(0.0f, 1.01f, -0.25f, 1.97f, -0.7f, 2.8f)
            lineToRelative(1.46f, 1.46f)
            curveTo(19.54f, 15.03f, 20.0f, 13.57f, 20.0f, 12.0f)
            curveToRelative(0.0f, -4.42f, -3.58f, -8.0f, -8.0f, -8.0f)
            close()
            moveTo(12.0f, 18.0f)
            curveToRelative(-3.31f, 0.0f, -6.0f, -2.69f, -6.0f, -6.0f)
            curveToRelative(0.0f, -1.01f, 0.25f, -1.97f, 0.7f, -2.8f)
            lineTo(5.24f, 7.74f)
            curveTo(4.46f, 8.97f, 4.0f, 10.43f, 4.0f, 12.0f)
            curveToRelative(0.0f, 4.42f, 3.58f, 8.0f, 8.0f, 8.0f)
            verticalLineToRelative(3.0f)
            lineToRelative(4.0f, -4.0f)
            lineToRelative(-4.0f, -4.0f)
            verticalLineToRelative(3.0f)
            close()
        }
    }

    val Terminal: ImageVector by lazy {
        buildIcon("Terminal") {
            moveTo(20.0f, 4.0f)
            horizontalLineTo(4.0f)
            curveTo(2.89f, 4.0f, 2.0f, 4.9f, 2.0f, 6.0f)
            verticalLineToRelative(12.0f)
            curveToRelative(0.0f, 1.1f, 0.89f, 2.0f, 2.0f, 2.0f)
            horizontalLineToRelative(16.0f)
            curveToRelative(1.1f, 0.0f, 2.0f, -0.9f, 2.0f, -2.0f)
            verticalLineTo(6.0f)
            curveTo(22.0f, 4.9f, 21.11f, 4.0f, 20.0f, 4.0f)
            close()
            moveTo(20.0f, 18.0f)
            horizontalLineTo(4.0f)
            verticalLineTo(8.0f)
            horizontalLineToRelative(16.0f)
            verticalLineTo(18.0f)
            close()
            moveTo(18.0f, 17.0f)
            horizontalLineToRelative(-6.0f)
            verticalLineToRelative(-2.0f)
            horizontalLineToRelative(6.0f)
            verticalLineTo(17.0f)
            close()
            moveTo(7.5f, 17.0f)
            lineToRelative(-1.41f, -1.41f)
            lineTo(8.67f, 13.0f)
            lineToRelative(-2.59f, -2.59f)
            lineTo(7.5f, 9.0f)
            lineToRelative(4.0f, 4.0f)
            lineTo(7.5f, 17.0f)
            close()
        }
    }

    val Timeline: ImageVector by lazy {
        buildIcon("Timeline") {
            moveTo(23.0f, 8.0f)
            curveToRelative(0.0f, 1.1f, -0.9f, 2.0f, -2.0f, 2.0f)
            curveToRelative(-0.18f, 0.0f, -0.35f, -0.02f, -0.51f, -0.07f)
            lineToRelative(-3.56f, 3.55f)
            curveTo(16.98f, 13.64f, 17.0f, 13.82f, 17.0f, 14.0f)
            curveToRelative(0.0f, 1.1f, -0.9f, 2.0f, -2.0f, 2.0f)
            reflectiveCurveToRelative(-2.0f, -0.9f, -2.0f, -2.0f)
            curveToRelative(0.0f, -0.18f, 0.02f, -0.36f, 0.07f, -0.52f)
            lineToRelative(-2.55f, -2.55f)
            curveTo(10.36f, 10.98f, 10.18f, 11.0f, 10.0f, 11.0f)
            reflectiveCurveToRelative(-0.36f, -0.02f, -0.52f, -0.07f)
            lineToRelative(-4.55f, 4.56f)
            curveTo(4.98f, 15.65f, 5.0f, 15.82f, 5.0f, 16.0f)
            curveToRelative(0.0f, 1.1f, -0.9f, 2.0f, -2.0f, 2.0f)
            reflectiveCurveToRelative(-2.0f, -0.9f, -2.0f, -2.0f)
            reflectiveCurveToRelative(0.9f, -2.0f, 2.0f, -2.0f)
            curveToRelative(0.18f, 0.0f, 0.35f, 0.02f, 0.51f, 0.07f)
            lineToRelative(4.56f, -4.55f)
            curveTo(8.02f, 9.36f, 8.0f, 9.18f, 8.0f, 9.0f)
            curveToRelative(0.0f, -1.1f, 0.9f, -2.0f, 2.0f, -2.0f)
            reflectiveCurveToRelative(2.0f, 0.9f, 2.0f, 2.0f)
            curveToRelative(0.0f, 0.18f, -0.02f, 0.36f, -0.07f, 0.52f)
            lineToRelative(2.55f, 2.55f)
            curveTo(14.64f, 12.02f, 14.82f, 12.0f, 15.0f, 12.0f)
            reflectiveCurveToRelative(0.36f, 0.02f, 0.52f, 0.07f)
            lineToRelative(3.55f, -3.56f)
            curveTo(19.02f, 8.35f, 19.0f, 8.18f, 19.0f, 8.0f)
            curveToRelative(0.0f, -1.1f, 0.9f, -2.0f, 2.0f, -2.0f)
            reflectiveCurveTo(23.0f, 6.9f, 23.0f, 8.0f)
            close()
        }
    }

    val Tune: ImageVector by lazy {
        buildIcon("Tune") {
            moveTo(3.0f, 17.0f)
            verticalLineToRelative(2.0f)
            horizontalLineToRelative(6.0f)
            verticalLineToRelative(-2.0f)
            lineTo(3.0f, 17.0f)
            close()
            moveTo(3.0f, 5.0f)
            verticalLineToRelative(2.0f)
            horizontalLineToRelative(10.0f)
            lineTo(13.0f, 5.0f)
            lineTo(3.0f, 5.0f)
            close()
            moveTo(13.0f, 21.0f)
            verticalLineToRelative(-2.0f)
            horizontalLineToRelative(8.0f)
            verticalLineToRelative(-2.0f)
            horizontalLineToRelative(-8.0f)
            verticalLineToRelative(-2.0f)
            horizontalLineToRelative(-2.0f)
            verticalLineToRelative(6.0f)
            horizontalLineToRelative(2.0f)
            close()
            moveTo(7.0f, 9.0f)
            verticalLineToRelative(2.0f)
            lineTo(3.0f, 11.0f)
            verticalLineToRelative(2.0f)
            horizontalLineToRelative(4.0f)
            verticalLineToRelative(2.0f)
            horizontalLineToRelative(2.0f)
            lineTo(9.0f, 9.0f)
            lineTo(7.0f, 9.0f)
            close()
            moveTo(21.0f, 13.0f)
            verticalLineToRelative(-2.0f)
            lineTo(11.0f, 11.0f)
            verticalLineToRelative(2.0f)
            horizontalLineToRelative(10.0f)
            close()
            moveTo(15.0f, 9.0f)
            horizontalLineToRelative(2.0f)
            lineTo(17.0f, 7.0f)
            horizontalLineToRelative(4.0f)
            lineTo(21.0f, 5.0f)
            horizontalLineToRelative(-4.0f)
            lineTo(17.0f, 3.0f)
            horizontalLineToRelative(-2.0f)
            verticalLineToRelative(6.0f)
            close()
        }
    }

    val Visibility: ImageVector by lazy {
        buildIcon("Visibility") {
            moveTo(12.0f, 4.5f)
            curveTo(7.0f, 4.5f, 2.73f, 7.61f, 1.0f, 12.0f)
            curveToRelative(1.73f, 4.39f, 6.0f, 7.5f, 11.0f, 7.5f)
            reflectiveCurveToRelative(9.27f, -3.11f, 11.0f, -7.5f)
            curveToRelative(-1.73f, -4.39f, -6.0f, -7.5f, -11.0f, -7.5f)
            close()
            moveTo(12.0f, 17.0f)
            curveToRelative(-2.76f, 0.0f, -5.0f, -2.24f, -5.0f, -5.0f)
            reflectiveCurveToRelative(2.24f, -5.0f, 5.0f, -5.0f)
            reflectiveCurveToRelative(5.0f, 2.24f, 5.0f, 5.0f)
            reflectiveCurveToRelative(-2.24f, 5.0f, -5.0f, 5.0f)
            close()
            moveTo(12.0f, 9.0f)
            curveToRelative(-1.66f, 0.0f, -3.0f, 1.34f, -3.0f, 3.0f)
            reflectiveCurveToRelative(1.34f, 3.0f, 3.0f, 3.0f)
            reflectiveCurveToRelative(3.0f, -1.34f, 3.0f, -3.0f)
            reflectiveCurveToRelative(-1.34f, -3.0f, -3.0f, -3.0f)
            close()
        }
    }

    val VisibilityOff: ImageVector by lazy {
        buildIcon("VisibilityOff") {
            moveTo(12.0f, 7.0f)
            curveToRelative(2.76f, 0.0f, 5.0f, 2.24f, 5.0f, 5.0f)
            curveToRelative(0.0f, 0.65f, -0.13f, 1.26f, -0.36f, 1.83f)
            lineToRelative(2.92f, 2.92f)
            curveToRelative(1.51f, -1.26f, 2.7f, -2.89f, 3.43f, -4.75f)
            curveToRelative(-1.73f, -4.39f, -6.0f, -7.5f, -11.0f, -7.5f)
            curveToRelative(-1.4f, 0.0f, -2.74f, 0.25f, -3.98f, 0.7f)
            lineToRelative(2.16f, 2.16f)
            curveTo(10.74f, 7.13f, 11.35f, 7.0f, 12.0f, 7.0f)
            close()
            moveTo(2.0f, 4.27f)
            lineToRelative(2.28f, 2.28f)
            lineToRelative(0.46f, 0.46f)
            curveTo(3.08f, 8.3f, 1.78f, 10.02f, 1.0f, 12.0f)
            curveToRelative(1.73f, 4.39f, 6.0f, 7.5f, 11.0f, 7.5f)
            curveToRelative(1.55f, 0.0f, 3.03f, -0.3f, 4.38f, -0.84f)
            lineToRelative(0.42f, 0.42f)
            lineTo(19.73f, 22.0f)
            lineTo(21.0f, 20.73f)
            lineTo(3.27f, 3.0f)
            lineTo(2.0f, 4.27f)
            close()
            moveTo(7.53f, 9.8f)
            lineToRelative(1.55f, 1.55f)
            curveToRelative(-0.05f, 0.21f, -0.08f, 0.43f, -0.08f, 0.65f)
            curveToRelative(0.0f, 1.66f, 1.34f, 3.0f, 3.0f, 3.0f)
            curveToRelative(0.22f, 0.0f, 0.44f, -0.03f, 0.65f, -0.08f)
            lineToRelative(1.55f, 1.55f)
            curveToRelative(-0.67f, 0.33f, -1.41f, 0.53f, -2.2f, 0.53f)
            curveToRelative(-2.76f, 0.0f, -5.0f, -2.24f, -5.0f, -5.0f)
            curveToRelative(0.0f, -0.79f, 0.2f, -1.53f, 0.53f, -2.2f)
            close()
            moveTo(11.84f, 9.02f)
            lineToRelative(3.15f, 3.15f)
            lineToRelative(0.02f, -0.16f)
            curveToRelative(0.0f, -1.66f, -1.34f, -3.0f, -3.0f, -3.0f)
            lineToRelative(-0.17f, 0.01f)
            close()
        }
    }

    val AnalyticsOutlined: ImageVector by lazy {
        buildIcon("AnalyticsOutlined") {
            moveTo(19.0f, 3.0f)
            horizontalLineTo(5.0f)
            curveTo(3.9f, 3.0f, 3.0f, 3.9f, 3.0f, 5.0f)
            verticalLineToRelative(14.0f)
            curveToRelative(0.0f, 1.1f, 0.9f, 2.0f, 2.0f, 2.0f)
            horizontalLineToRelative(14.0f)
            curveToRelative(1.1f, 0.0f, 2.0f, -0.9f, 2.0f, -2.0f)
            verticalLineTo(5.0f)
            curveTo(21.0f, 3.9f, 20.1f, 3.0f, 19.0f, 3.0f)
            close()
            moveTo(19.0f, 19.0f)
            horizontalLineTo(5.0f)
            verticalLineTo(5.0f)
            horizontalLineToRelative(14.0f)
            verticalLineTo(19.0f)
            close()
        }
    }

    val MoreHorizOutlined: ImageVector by lazy {
        buildIcon("MoreHorizOutlined") {
            moveTo(6.0f, 10.0f)
            curveToRelative(-1.1f, 0.0f, -2.0f, 0.9f, -2.0f, 2.0f)
            reflectiveCurveToRelative(0.9f, 2.0f, 2.0f, 2.0f)
            reflectiveCurveToRelative(2.0f, -0.9f, 2.0f, -2.0f)
            reflectiveCurveToRelative(-0.9f, -2.0f, -2.0f, -2.0f)
            close()
            moveTo(18.0f, 10.0f)
            curveToRelative(-1.1f, 0.0f, -2.0f, 0.9f, -2.0f, 2.0f)
            reflectiveCurveToRelative(0.9f, 2.0f, 2.0f, 2.0f)
            reflectiveCurveToRelative(2.0f, -0.9f, 2.0f, -2.0f)
            reflectiveCurveToRelative(-0.9f, -2.0f, -2.0f, -2.0f)
            close()
            moveTo(12.0f, 10.0f)
            curveToRelative(-1.1f, 0.0f, -2.0f, 0.9f, -2.0f, 2.0f)
            reflectiveCurveToRelative(0.9f, 2.0f, 2.0f, 2.0f)
            reflectiveCurveToRelative(2.0f, -0.9f, 2.0f, -2.0f)
            reflectiveCurveToRelative(-0.9f, -2.0f, -2.0f, -2.0f)
            close()
        }
    }

    val PieChartOutlined: ImageVector by lazy {
        buildIcon("PieChartOutlined") {
            moveTo(12.0f, 2.0f)
            curveTo(6.48f, 2.0f, 2.0f, 6.48f, 2.0f, 12.0f)
            reflectiveCurveToRelative(4.48f, 10.0f, 10.0f, 10.0f)
            reflectiveCurveToRelative(10.0f, -4.48f, 10.0f, -10.0f)
            reflectiveCurveTo(17.52f, 2.0f, 12.0f, 2.0f)
            close()
            moveTo(19.93f, 11.0f)
            lineTo(13.0f, 11.0f)
            lineTo(13.0f, 4.07f)
            curveToRelative(3.61f, 0.45f, 6.48f, 3.32f, 6.93f, 6.93f)
            close()
            moveTo(4.0f, 12.0f)
            curveToRelative(0.0f, -4.07f, 3.06f, -7.44f, 7.0f, -7.93f)
            verticalLineToRelative(15.86f)
            curveToRelative(-3.94f, -0.49f, -7.0f, -3.86f, -7.0f, -7.93f)
            close()
            moveTo(13.0f, 19.93f)
            lineTo(13.0f, 13.0f)
            horizontalLineToRelative(6.93f)
            curveToRelative(-0.45f, 3.61f, -3.32f, 6.48f, -6.93f, 6.93f)
            close()
        }
    }

}
