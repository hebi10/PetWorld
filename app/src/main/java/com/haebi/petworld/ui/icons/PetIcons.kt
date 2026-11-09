package com.haebi.petworld.ui.icons

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccountBalanceWallet
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Assessment
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Bedtime
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Collections
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.DirectionsWalk
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.EventAvailable
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.FileUpload
import androidx.compose.material.icons.outlined.FilterList
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material.icons.outlined.Healing
import androidx.compose.material.icons.outlined.HelpOutline
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Insights
import androidx.compose.material.icons.outlined.ListAlt
import androidx.compose.material.icons.outlined.LocalHospital
import androidx.compose.material.icons.outlined.LockOutline
import androidx.compose.material.icons.outlined.Logout
import androidx.compose.material.icons.outlined.Medication
import androidx.compose.material.icons.outlined.MonitorWeight
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.outlined.NotificationsNone
import androidx.compose.material.icons.outlined.Pets
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.ReceiptLong
import androidx.compose.material.icons.outlined.Restaurant
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Sort
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material.icons.outlined.Vaccines
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material.icons.outlined.WaterDrop
import androidx.compose.material.icons.outlined.Wc
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * PetWorld single-stroke icon vocabulary.
 * Color and size are owned by the calling Material theme, not individual symbols.
 */
object PetIcons {
    // Top-level navigation
    val Home: ImageVector = Icons.Outlined.Home
    val Records: ImageVector = Icons.Outlined.ListAlt
    val Health: ImageVector = Icons.Outlined.FavoriteBorder
    val Statistics: ImageVector = Icons.Outlined.Insights
    val More: ImageVector = Icons.Outlined.MoreHoriz

    // Pet-specific records
    val Profile: ImageVector = Icons.Outlined.Pets
    val Food: ImageVector = Icons.Outlined.Restaurant
    val Water: ImageVector = Icons.Outlined.WaterDrop
    val Elimination: ImageVector = Icons.Outlined.Wc
    val Activity: ImageVector = Icons.Outlined.DirectionsWalk
    val Sleep: ImageVector = Icons.Outlined.Bedtime
    val Weight: ImageVector = Icons.Outlined.MonitorWeight

    // Health and care
    val Symptoms: ImageVector = Icons.Outlined.Healing
    val Vet: ImageVector = Icons.Outlined.LocalHospital
    val Vaccination: ImageVector = Icons.Outlined.Vaccines
    val Medication: ImageVector = Icons.Outlined.Medication
    val Prevention: ImageVector = Icons.Outlined.EventAvailable

    // Long-term management
    val Expenses: ImageVector = Icons.Outlined.AccountBalanceWallet
    val Household: ImageVector = Icons.Outlined.Groups
    val Album: ImageVector = Icons.Outlined.PhotoLibrary
    val Report: ImageVector = Icons.Outlined.Assessment
    val Observation: ImageVector = Icons.Outlined.AutoAwesome

    // Shared actions
    val Add: ImageVector = Icons.Outlined.Add
    val Edit: ImageVector = Icons.Outlined.Edit
    val Delete: ImageVector = Icons.Outlined.DeleteOutline
    val Search: ImageVector = Icons.Outlined.Search
    val Filter: ImageVector = Icons.Outlined.FilterList
    val Sort: ImageVector = Icons.Outlined.Sort
    val Calendar: ImageVector = Icons.Outlined.CalendarMonth
    val Notifications: ImageVector = Icons.Outlined.NotificationsNone
    val Share: ImageVector = Icons.Outlined.Share
    val Export: ImageVector = Icons.Outlined.FileDownload
    val Import: ImageVector = Icons.Outlined.FileUpload
    val Settings: ImageVector = Icons.Outlined.Settings
    val Help: ImageVector = Icons.Outlined.HelpOutline
    val Info: ImageVector = Icons.Outlined.Info
    val Back: ImageVector = Icons.Outlined.ArrowBack
    val Close: ImageVector = Icons.Outlined.Close
    val Done: ImageVector = Icons.Outlined.Check
    val Warning: ImageVector = Icons.Outlined.WarningAmber
    val Attachment: ImageVector = Icons.Outlined.AttachFile
    val Camera: ImageVector = Icons.Outlined.PhotoCamera
    val Gallery: ImageVector = Icons.Outlined.Collections
    val Sync: ImageVector = Icons.Outlined.Sync
    val Lock: ImageVector = Icons.Outlined.LockOutline
    val Logout: ImageVector = Icons.Outlined.Logout

    val navigation = listOf(Home, Records, Health, Statistics, More)
    val daily = listOf(Food, Water, Elimination, Activity, Weight)
}
