package org.uci.proxy.ui.icons

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.vectorResource
import org.uci.proxy.resources.*

/*
 * Iconos de la app: solo los que se usan, copiados de Material Symbols (contorno, 24 dp, licencia Apache 2.0 en
 * shared/third-party-licenses). Así no se arrastra material-icons-extended, que pesa mucho en iOS y escritorio.
 * Para añadir uno: bajar su SVG de github.com/google/material-design-icons (symbols/web/<nombre>/materialsymbolsoutlined)
 * y convertirlo a shared/composeResources/drawable/ic_<nombre>.xml como los demás.
 */
object UciIcons {
    val Power: ImageVector @Composable get() = icon(Res.drawable.ic_power_settings_new)
    val Shield: ImageVector @Composable get() = icon(Res.drawable.ic_shield)
    val Terminal: ImageVector @Composable get() = icon(Res.drawable.ic_terminal)
    val Person: ImageVector @Composable get() = icon(Res.drawable.ic_person)
    val Swap: ImageVector @Composable get() = icon(Res.drawable.ic_swap_horiz)
    val Refresh: ImageVector @Composable get() = icon(Res.drawable.ic_refresh)
    val ArrowDown: ImageVector @Composable get() = icon(Res.drawable.ic_arrow_downward)
    val ArrowUp: ImageVector @Composable get() = icon(Res.drawable.ic_arrow_upward)
    val Copy: ImageVector @Composable get() = icon(Res.drawable.ic_content_copy)
    val Globe: ImageVector @Composable get() = icon(Res.drawable.ic_public)
    val Lock: ImageVector @Composable get() = icon(Res.drawable.ic_lock)
    val Visibility: ImageVector @Composable get() = icon(Res.drawable.ic_visibility)
    val VisibilityOff: ImageVector @Composable get() = icon(Res.drawable.ic_visibility_off)
    val Search: ImageVector @Composable get() = icon(Res.drawable.ic_search)
    val Close: ImageVector @Composable get() = icon(Res.drawable.ic_close)
    val Check: ImageVector @Composable get() = icon(Res.drawable.ic_check)
    val CheckCircle: ImageVector @Composable get() = icon(Res.drawable.ic_check_circle)
    val Error: ImageVector @Composable get() = icon(Res.drawable.ic_error)
    val Warning: ImageVector @Composable get() = icon(Res.drawable.ic_warning)
    val Info: ImageVector @Composable get() = icon(Res.drawable.ic_info)
    val ChevronRight: ImageVector @Composable get() = icon(Res.drawable.ic_chevron_right)
    val Back: ImageVector @Composable get() = icon(Res.drawable.ic_arrow_back)
    val Add: ImageVector @Composable get() = icon(Res.drawable.ic_add)
    val Delete: ImageVector @Composable get() = icon(Res.drawable.ic_delete)
    val Edit: ImageVector @Composable get() = icon(Res.drawable.ic_edit)
    val DragHandle: ImageVector @Composable get() = icon(Res.drawable.ic_drag_indicator)
    val File: ImageVector @Composable get() = icon(Res.drawable.ic_description)
    val Tune: ImageVector @Composable get() = icon(Res.drawable.ic_tune)
    val Logs: ImageVector @Composable get() = icon(Res.drawable.ic_data_object)
    val Badge: ImageVector @Composable get() = icon(Res.drawable.ic_badge)
    val Dns: ImageVector @Composable get() = icon(Res.drawable.ic_dns)
    val VpnLock: ImageVector @Composable get() = icon(Res.drawable.ic_vpn_lock)
    val Split: ImageVector @Composable get() = icon(Res.drawable.ic_alt_route)
    val Filter: ImageVector @Composable get() = icon(Res.drawable.ic_filter_list)
    val More: ImageVector @Composable get() = icon(Res.drawable.ic_more_vert)
    val Schedule: ImageVector @Composable get() = icon(Res.drawable.ic_schedule)
    val Folder: ImageVector @Composable get() = icon(Res.drawable.ic_folder_open)
    val Hub: ImageVector @Composable get() = icon(Res.drawable.ic_hub)
    val Key: ImageVector @Composable get() = icon(Res.drawable.ic_key)
    val Speed: ImageVector @Composable get() = icon(Res.drawable.ic_speed)
    val Sync: ImageVector @Composable get() = icon(Res.drawable.ic_sync)
    val Download: ImageVector @Composable get() = icon(Res.drawable.ic_download)
    val Upload: ImageVector @Composable get() = icon(Res.drawable.ic_upload)
    val Pause: ImageVector @Composable get() = icon(Res.drawable.ic_pause)
    val Play: ImageVector @Composable get() = icon(Res.drawable.ic_play_arrow)
}

@Composable
private fun icon(resource: DrawableResource): ImageVector = vectorResource(resource)
