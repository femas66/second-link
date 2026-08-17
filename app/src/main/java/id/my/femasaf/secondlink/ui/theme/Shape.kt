package id.my.femasaf.secondlink.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

// DESIGN.md rounded scale: sm 4dp, md 12dp, lg 16dp.
val ShapeSm = RoundedCornerShape(4.dp)
val ShapeMd = RoundedCornerShape(12.dp)
val ShapeLg = RoundedCornerShape(16.dp)
val ShapePill = RoundedCornerShape(100)

val SecondLinkShapes = Shapes(
    extraSmall = ShapeSm,
    small = ShapeSm,
    medium = ShapeMd,
    large = ShapeLg,
    extraLarge = ShapeLg
)
