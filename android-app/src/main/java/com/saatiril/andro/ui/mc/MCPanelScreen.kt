package com.saatiril.andro.ui.mc

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.saatiril.andro.data.AdminViewModel
import com.saatiril.andro.data.CameraModes
import com.saatiril.andro.data.ConnectionState
import com.saatiril.andro.data.OperatorViewModel
import com.saatiril.andro.data.Student
import com.saatiril.andro.data.getActiveChannel
import com.saatiril.andro.data.isActiveStatus
import kotlinx.coroutines.delay

private val BG = Color(0xFF1a0b2e)
private val PANEL = Color(0xFF2a164a)
private val CARD = Color(0xFF3b2263)
private val BORDER = Color(0xFF533485)
private val GOLD = Color(0xFFd4af37)
private val MUTED = Color(0xFFc4b5fd)
private val GREEN = Color(0xFF4ade80)
private val RED = Color(0xFFef4444)
private val CYAN = Color(0xFF06b6d4)
private val AMBER = Color(0xFFfbbf24)

/**
 * MC Panel Screen — the main MC panel when connected to the admin's server.
 *
 * Shows the live queue of students and a PANGGIL button to call the next
 * student to the stage. This is the same UI as the admin's McScreen but
 * connected to a REMOTE server via [OperatorViewModel].
 *
 * The MC can:
 *  - See the current active student (being photographed)
 *  - See the next pending student
 *  - Press PANGGIL to call the next student
 *  - Reset a student if needed
 *  - See status (active/pending/done) for each student
 */
@Composable
fun MCPanelScreen(
    adminViewModel: AdminViewModel,
    opViewModel: OperatorViewModel = viewModel()
) {
    val context = LocalContext.current
    val project by opViewModel.project.collectAsState()
    val connectionState by opViewModel.connectionState.collectAsState()
    val myChannel by opViewModel.myChannel.collectAsState()
    val opQueue by opViewModel.opQueue.collectAsState()
    val currentTarget by opViewModel.currentTarget.collectAsState()
    val scrollState = rememberScrollState()

    // Lock screen — prevent accidental exit via back button
    com.saatiril.andro.ui.util.LockScreenHandler {
        opViewModel.disconnect()
        adminViewModel.backToRoleSelect()
    }

    // Auto-disconnect handling — give auto-reconnect a grace period (~2s).
    // The connectionState key does NOT change while SocketManager retries
    // (it stays DISCONNECTED until the socket.io CONNECT ack arrives), so a
    // successful sub-2s reconnect must NOT kick the MC back to the connect
    // screen: after the delay, re-read the CURRENT state from the flow
    // instead of trusting the stale captured value.
    LaunchedEffect(connectionState) {
        if (connectionState == ConnectionState.DISCONNECTED) {
            delay(2000)
            if (opViewModel.connectionState.value == ConnectionState.DISCONNECTED) {
                adminViewModel.mcDisconnected()
            }
        }
    }

    val db = project?.database ?: emptyList()
    val mode = project?.config?.mode ?: CameraModes.SINGLE
    val isPhotoshoot = CameraModes.isPhotoshootMode(mode)
    val isDual = CameraModes.isDualMode(mode)

    val channelStudents = if (isPhotoshoot) db else db.filter { it.assignedChannel == myChannel }
    val pending = channelStudents.filter { it.status == "pending" }
    val active = if (isPhotoshoot) emptyList() else channelStudents.filter { isActiveStatus(it.status) }
    val done = channelStudents.filter { it.status == "done" }
    val hasActive = active.isNotEmpty()
    val nextPending = if (!isPhotoshoot) channelStudents.firstOrNull { it.status == "pending" } else null

    // ── Photoshoot multi-select state ───────────────────────────────
    // SnapshotStateList so Compose recomposes whenever selection changes.
    // Order is preserved (matches Electron's selectedStudentIds string[]):
    // the batch send emits MC_CALLs in selection order, matching the operator
    // queue display.
    var searchQuery by remember { mutableStateOf("") }
    val selectedIds: SnapshotStateList<String> = remember { mutableStateListOf() }

    // The displayed queue in photoshoot mode — respects the search filter.
    // In photoshoot mode, ALL students are shown (no channel filter), but
    // we filter by search query (matches the Electron mc-panel's
    // displayedAntrean logic).
    val displayedAntrean: List<Student> = if (isPhotoshoot) {
        val q = searchQuery.trim().lowercase()
        if (q.isEmpty()) channelStudents
        else channelStudents.filter {
            it.nama.lowercase().contains(q) || it.nim.lowercase().contains(q)
        }
    } else {
        channelStudents
    }

    // Resolve the selected IDs to actual Student objects (filtering out any
    // that are no longer 'pending' — e.g. already sent in a previous batch).
    // Matches Electron's selectedStudents memo: `if (s && s.status === 'pending')`.
    val selectedStudents: List<Student> = if (isPhotoshoot) {
        // Look up by ID in the live DB so status changes (e.g. 'sent' after a
        // previous batch) are reflected — a selected student that is no longer
        // pending is silently dropped from the send batch.
        selectedIds.mapNotNull { id ->
            db.firstOrNull { it.id == id }?.takeIf { it.status == "pending" }
        }
    } else {
        emptyList()
    }

    // ── Photoshoot multi-select helpers ─────────────────────────────
    fun toggleStudentSelection(id: String) {
        val idx = selectedIds.indexOf(id)
        if (idx >= 0) selectedIds.removeAt(idx) else selectedIds.add(id)
    }

    // Select all currently-displayed pending students (respects the search
    // filter — if MC filtered to e.g. "toga", only those get selected).
    // Accumulates onto existing selection (matches Electron selectAllDisplayed).
    fun selectAllDisplayed() {
        val seen = selectedIds.toHashSet()
        for (s in displayedAntrean) {
            if (s.status == "pending" && !seen.contains(s.id)) {
                selectedIds.add(s.id)
                seen.add(s.id)
            }
        }
    }

    // Deselect all currently-displayed (respects search filter — only removes
    // the ones currently visible so MC can prune part of a big selection).
    fun deselectAllDisplayed() {
        val displayedIdSet = displayedAntrean.map { it.id }.toHashSet()
        selectedIds.removeAll(displayedIdSet)
    }

    fun clearSelection() {
        selectedIds.clear()
    }

    // Batch send — for each selected student: mark 'sent' + emit MC_CALL
    // (to both channels in dual mode, to channel 1 only in single mode).
    // The OperatorViewModel.sendBatchToOperator handles the local DB update +
    // per-(student × channel) MC_CALL emit. The server's handleMcCall is
    // mode-aware so it won't revert "sent" back to "active_$channel".
    // Clear selection at the end + reset search query so the MC can see the
    // full queue again (the just-sent students are now 'sent', filtered out).
    fun handleSendBatchToOperator() {
        if (selectedStudents.isEmpty()) return
        val channels = if (isDual) listOf(1, 2) else listOf(1)
        opViewModel.sendBatchToOperator(selectedStudents, channels)
        searchQuery = ""
        clearSelection()
    }

    // Auto-scroll to active student position — bring to TOP of visible area
    val activeStudentId = active.firstOrNull()?.id
    LaunchedEffect(activeStudentId, done.size) {
        delay(150)
        val activeIdx = channelStudents.indexOfFirst { it.id == activeStudentId }
        if (activeIdx >= 0) {
            // Scroll so active student is at TOP of visible area
            val targetScroll = (activeIdx * 38 - 38).coerceAtLeast(0)
            scrollState.animateScrollTo(targetScroll.coerceAtMost(scrollState.maxValue))
        } else {
            scrollState.animateScrollTo(0)
        }
    }

    // ORIGINAL ORDER — no sorting, keep queue numbers
    val queueFilter = channelStudents

    Column(
        modifier = Modifier.fillMaxSize().background(BG)
    ) {
        // ── Top bar: connection + disconnect ──
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            val connColor = when (connectionState) {
                ConnectionState.AUTHENTICATED, ConnectionState.WAITING_FOR_DATA -> GREEN
                ConnectionState.CONNECTING, ConnectionState.RECONNECTING, ConnectionState.AUTHENTICATING -> GOLD
                else -> RED
            }
            Box(Modifier.size(8.dp).clip(RoundedCornerShape(4.dp)).background(connColor))
            Text("MC Ch.$myChannel", style = TextStyle(color = connColor, fontSize = 12.sp, fontWeight = FontWeight.Bold), modifier = Modifier.weight(1f))
            if (isDual) {
                Card(modifier = Modifier.clip(RoundedCornerShape(10.dp)).clickable { },
                    colors = CardDefaults.cardColors(containerColor = PANEL), shape = RoundedCornerShape(10.dp)) {
                    Text("Ch.$myChannel", modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp), style = TextStyle(color = GOLD, fontSize = 11.sp, fontWeight = FontWeight.Bold))
                }
            }
            // Disconnect button
            Card(
                modifier = Modifier.size(36.dp).clip(RoundedCornerShape(8.dp))
                    .clickable {
                        opViewModel.disconnect()
                        adminViewModel.backToRoleSelect()
                    },
                colors = CardDefaults.cardColors(containerColor = PANEL),
                shape = RoundedCornerShape(8.dp)
            ) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.Logout, contentDescription = "Disconnect", tint = RED, modifier = Modifier.size(18.dp))
                }
            }
        }

        // ── DOMINANT NAME CARD ──
        if (!isPhotoshoot) {
            val displayName = when {
                hasActive -> active.firstOrNull()?.nama?.ifBlank { null } ?: active.firstOrNull()?.nim ?: ""
                nextPending != null -> nextPending.nama.ifBlank { nextPending.nim }
                else -> "Antrean Habis"
            }
            val displayNim = when {
                hasActive -> active.firstOrNull()?.nim ?: ""
                nextPending != null -> nextPending?.nim ?: ""
                else -> ""
            }
            val nameColor = when {
                hasActive -> GOLD
                nextPending != null -> Color.White
                else -> MUTED
            }
            val namePrefix = when {
                hasActive -> "◆ "
                nextPending != null -> "▶ "
                else -> ""
            }

            // ── Highlight (matches the React panels' getRowStyle):
            //   active (being photographed) → strong gold tint bg + 2dp gold border
            //     (MOST prominent — operator can compare this name with what MC sent).
            //   next pending (ready to call) → subtle gold tint bg + 2dp gold border
            //     (button-like — MC easily reads + presses PANGGIL).
            //   else (habis) → plain panel + thin border.
            val cardBg = if (hasActive) GOLD.copy(alpha = 0.2f) else if (nextPending != null) GOLD.copy(alpha = 0.1f) else PANEL
            val cardBorderWidth = if (hasActive || nextPending != null) 2.dp else 1.dp
            val cardBorderColor = when {
                hasActive -> GOLD
                nextPending != null -> GOLD.copy(alpha = 0.7f)
                else -> BORDER.copy(alpha = 0.4f)
            }
            Card(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                colors = CardDefaults.cardColors(containerColor = cardBg),
                shape = RoundedCornerShape(10.dp),
                border = androidx.compose.foundation.BorderStroke(cardBorderWidth, cardBorderColor)
            ) {
                Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        "$namePrefix$displayName",
                        style = TextStyle(color = nameColor, fontSize = 22.sp, fontWeight = FontWeight.Black, lineHeight = 24.sp),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (displayNim.isNotEmpty()) {
                        Text(displayNim, style = TextStyle(color = MUTED, fontSize = 12.sp, fontFamily = FontFamily.Monospace))
                    }
                }
            }

            // ── PANGGIL / TUNGGU / RESET button ──
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Button(
                    onClick = { nextPending?.let { opViewModel.callStudent(it, myChannel) } },
                    modifier = Modifier.weight(1f).height(48.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = if (!hasActive && nextPending != null) GREEN else BORDER),
                    shape = RoundedCornerShape(10.dp),
                    enabled = !hasActive && nextPending != null
                ) {
                    if (hasActive) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), color = GOLD, strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                        Text("TUNGGU OPERATOR…", color = GOLD, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    } else if (nextPending != null) {
                        Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(20.dp), tint = BG)
                        Spacer(Modifier.width(8.dp))
                        Text("PANGGIL", color = BG, fontSize = 16.sp, fontWeight = FontWeight.Black)
                    } else {
                        Text("HABIS", color = MUTED, fontSize = 13.sp)
                    }
                }
                if (hasActive) {
                    OutlinedButton(
                        onClick = { active.firstOrNull()?.let { opViewModel.resetStudent(it.id, getActiveChannel(it.status) ?: myChannel) } },
                        modifier = Modifier.height(48.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = AMBER),
                        shape = RoundedCornerShape(10.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, AMBER)
                    ) {
                        Icon(Icons.Default.Undo, contentDescription = "Reset", modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Reset", fontSize = 12.sp)
                    }
                }
            }

            // 4 stat pills (matches the Electron mc-panel + saatiril-mc.apk BLE
            // remote): ANTREAN / PROSES / SELESAI / TOTAL — each color-coded.
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                val pills = listOf(
                    Triple("ANTREAN", pending.size, MUTED),
                    Triple("PROSES", active.size, GOLD),
                    Triple("SELESAI", done.size, GREEN),
                    Triple("TOTAL", channelStudents.size, GOLD)
                )
                pills.forEach { (label, count, color) ->
                    Card(modifier = Modifier.weight(1f).clip(RoundedCornerShape(4.dp)),
                        colors = CardDefaults.cardColors(containerColor = color.copy(alpha = 0.15f)),
                        shape = RoundedCornerShape(4.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, color.copy(alpha = 0.3f))) {
                        Column(Modifier.padding(vertical = 2.dp, horizontal = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("$count", style = TextStyle(color = color, fontSize = 12.sp, fontWeight = FontWeight.Bold))
                            Text(label, style = TextStyle(color = color.copy(alpha = 0.7f), fontSize = 7.sp, fontWeight = FontWeight.Bold))
                        }
                    }
                }
            }
        } else {
            // Photoshoot mode — stat card + multi-select toolbar.
            // Mirrors the Electron mc-panel's photoshoot branch: MC doesn't
            // "call" students to the stage (operator picks from the queue),
            // instead MC SELECTS one or more pending students + sends them to
            // the operator queue in one batch via handleSendBatchToOperator.
            Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp), colors = CardDefaults.cardColors(containerColor = PANEL), shape = RoundedCornerShape(10.dp)) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Mode Photoshoot", style = TextStyle(color = GOLD, fontSize = 14.sp, fontWeight = FontWeight.Bold))
                    Text("Pilih peserta → kirim ke operator", style = TextStyle(color = MUTED, fontSize = 11.sp))
                }
            }

            // 4 stat pills (matches the non-photoshoot branch + Electron mc-panel).
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                val pills = listOf(
                    Triple("ANTREAN", pending.size, MUTED),
                    Triple("TERKIRIM", channelStudents.count { it.status == "sent" }, CYAN),
                    Triple("SELESAI", done.size, GREEN),
                    Triple("TOTAL", channelStudents.size, GOLD)
                )
                pills.forEach { (label, count, color) ->
                    Card(modifier = Modifier.weight(1f).clip(RoundedCornerShape(4.dp)),
                        colors = CardDefaults.cardColors(containerColor = color.copy(alpha = 0.15f)),
                        shape = RoundedCornerShape(4.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, color.copy(alpha = 0.3f))) {
                        Column(Modifier.padding(vertical = 2.dp, horizontal = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("$count", style = TextStyle(color = color, fontSize = 12.sp, fontWeight = FontWeight.Bold))
                            Text(label, style = TextStyle(color = color.copy(alpha = 0.7f), fontSize = 7.sp, fontWeight = FontWeight.Bold))
                        }
                    }
                }
            }

            // ── Search input ── (matches Electron mc-panel's search input)
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = { Text("Cari NIM atau Nama...", style = TextStyle(color = MUTED.copy(alpha = 0.5f), fontSize = 11.sp)) },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(14.dp), tint = MUTED) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp),
                shape = RoundedCornerShape(8.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White,
                    focusedBorderColor = GREEN,
                    unfocusedBorderColor = BORDER,
                    cursorColor = GREEN,
                    focusedLeadingIconColor = GREEN,
                    unfocusedLeadingIconColor = MUTED
                ),
                textStyle = TextStyle(color = Color.White, fontSize = 11.sp)
            )

            // ── Multi-select toolbar (photoshoot only) ──
            // Pilih Semua / Kosongkan / counter — matches Electron mc-panel
            // toolbar between the search input and the list.
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                // Pilih Semua — selects all currently-displayed pending students.
                // Disabled when no displayed pending students.
                val hasDisplayedPending = displayedAntrean.any { it.status == "pending" }
                OutlinedButton(
                    onClick = { selectAllDisplayed() },
                    enabled = hasDisplayedPending,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(6.dp),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = GREEN,
                        disabledContentColor = MUTED.copy(alpha = 0.4f)
                    ),
                    border = androidx.compose.foundation.BorderStroke(1.dp, if (hasDisplayedPending) GREEN.copy(alpha = 0.5f) else BORDER.copy(alpha = 0.4f)),
                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Icon(Icons.Default.CheckCircle, contentDescription = null, modifier = Modifier.size(12.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Pilih Semua", fontSize = 9.sp, fontWeight = FontWeight.SemiBold)
                }
                // Kosongkan — deselects all currently-displayed (respects
                // search filter — only removes the visible ones). Disabled when
                // the selection is empty.
                OutlinedButton(
                    onClick = { deselectAllDisplayed() },
                    enabled = selectedIds.isNotEmpty(),
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(6.dp),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MUTED,
                        disabledContentColor = MUTED.copy(alpha = 0.4f)
                    ),
                    border = androidx.compose.foundation.BorderStroke(1.dp, if (selectedIds.isNotEmpty()) MUTED.copy(alpha = 0.5f) else BORDER.copy(alpha = 0.4f)),
                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Icon(Icons.Default.Cancel, contentDescription = null, modifier = Modifier.size(12.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Kosongkan", fontSize = 9.sp, fontWeight = FontWeight.SemiBold)
                }
                // Counter — X terpilih
                Text(
                    "${selectedIds.size} terpilih",
                    style = TextStyle(
                        color = if (selectedIds.isNotEmpty()) GREEN else MUTED.copy(alpha = 0.6f),
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold
                    )
                )
            }
        }

        Spacer(Modifier.height(6.dp))

        // ── Queue list ──
        // Photoshoot: clickable rows + selected indicator (toggle on click).
        // Non-photoshoot: original behavior (first-pending highlight, no click).
        Column(
            modifier = Modifier.weight(1f).verticalScroll(scrollState).padding(horizontal = 8.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            if (isPhotoshoot) {
                Text(
                    "Antrean (${pending.size} menunggu${if (searchQuery.isNotBlank()) ", ${displayedAntrean.size} ditampilkan" else ""})",
                    style = TextStyle(color = MUTED, fontWeight = FontWeight.Bold, fontSize = 11.sp)
                )
                if (displayedAntrean.isEmpty()) {
                    Text(
                        if (channelStudents.isEmpty()) "Antrean kosong" else "Tidak ditemukan",
                        Modifier.padding(8.dp),
                        style = TextStyle(color = MUTED, fontSize = 12.sp)
                    )
                } else {
                    displayedAntrean.forEachIndexed { idx, s ->
                        MCPhotoshootQueueRow(
                            number = idx + 1,
                            student = s,
                            isSelected = selectedIds.contains(s.id),
                            onToggle = { toggleStudentSelection(s.id) }
                        )
                    }
                }
            } else {
                Text("Antrean Ch.$myChannel (${pending.size} menunggu)", style = TextStyle(color = MUTED, fontWeight = FontWeight.Bold, fontSize = 11.sp))
                if (queueFilter.isEmpty()) {
                    Text("Antrean habis", Modifier.padding(8.dp), style = TextStyle(color = MUTED, fontSize = 12.sp))
                } else {
                    queueFilter.forEachIndexed { idx, s ->
                        MCQueueRow(idx + 1, s, myChannel)
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
        }

        // ── Footer: SEND batch button (photoshoot only) ──
        // Mirrors the Electron mc-panel footer: shows selected count + first 3
        // names + "+N lainnya" preview, then the send button.
        if (isPhotoshoot) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                // Selected students preview (photoshoot only — show count +
                // first 3 names + "+N lainnya" when N > 3). Matches Electron
                // mc-panel footer preview block.
                if (selectedStudents.isNotEmpty()) {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = GREEN.copy(alpha = 0.1f)),
                        shape = RoundedCornerShape(6.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, GREEN.copy(alpha = 0.3f))
                    ) {
                        Column(Modifier.padding(horizontal = 8.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                            Text(
                                "${selectedStudents.size} Peserta Dipilih",
                                style = TextStyle(
                                    color = GREEN,
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            )
                            selectedStudents.take(3).forEach { s ->
                                Text(
                                    s.nama.ifBlank { "(tanpa nama)" },
                                    style = TextStyle(color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            if (selectedStudents.size > 3) {
                                Text(
                                    "+${selectedStudents.size - 3} lainnya",
                                    style = TextStyle(color = MUTED, fontSize = 9.sp, fontStyle = androidx.compose.ui.text.font.FontStyle.Italic)
                                )
                            }
                        }
                    }
                }

                // SEND button — shows the count, disabled when selection is
                // empty. Mirrors Electron mc-panel renderCallButton():
                //   selectedStudents.length > 0
                //     ? (dualPhotoshoot ? "KIRIM ${N} KE 2 KAMERA" : "KIRIM ${N} KE OPERATOR")
                //     : "PILIH PESERTA DULU"
                val canSend = selectedStudents.isNotEmpty()
                val btnBg = if (canSend) GREEN else PANEL
                val btnFg = if (canSend) BG else MUTED
                val btnBorder = if (canSend) GREEN else BORDER.copy(alpha = 0.4f)
                Button(
                    onClick = { handleSendBatchToOperator() },
                    enabled = canSend,
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = btnBg,
                        contentColor = btnFg,
                        disabledContainerColor = btnBg,
                        disabledContentColor = btnFg
                    ),
                    shape = RoundedCornerShape(10.dp),
                    border = androidx.compose.foundation.BorderStroke(2.dp, btnBorder)
                ) {
                    Icon(Icons.Default.Send, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        if (canSend) {
                            if (isDual) "KIRIM ${selectedStudents.size} KE 2 KAMERA"
                            else "KIRIM ${selectedStudents.size} KE OPERATOR"
                        } else {
                            "PILIH PESERTA DULU"
                        },
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Black
                    )
                }
            }
        }
    }
}

/**
 * Photoshoot queue row — clickable to toggle selection. Mirrors the Electron
 * mc-panel row rendering: each row has a checkbox-style icon (CheckSquare when
 * selected, Square when not); click toggles selection. Selected rows get an
 * emerald background + emerald name color + bold.
 */
@Composable
private fun MCPhotoshootQueueRow(
    number: Int,
    student: Student,
    isSelected: Boolean,
    onToggle: () -> Unit
) {
    val isDone = student.status == "done"
    val isSent = student.status == "sent"
    // Status color: done → green, sent → cyan (waiting for operator), pending
    // → muted (ready to be selected + sent).
    val statusColor = when {
        isDone -> GREEN.copy(alpha = 0.5f)
        isSent -> CYAN
        else -> MUTED
    }
    val rowBg = when {
        isSelected -> GREEN.copy(alpha = 0.18f)
        isDone -> PANEL.copy(alpha = 0.3f)
        isSent -> CYAN.copy(alpha = 0.08f)
        else -> PANEL
    }
    val rowBorderWidth = if (isSelected) 1.5.dp else 1.dp
    val rowBorderColor = when {
        isSelected -> GREEN
        else -> BORDER.copy(alpha = 0.2f)
    }
    Card(
        colors = CardDefaults.cardColors(containerColor = rowBg),
        shape = RoundedCornerShape(6.dp),
        border = androidx.compose.foundation.BorderStroke(rowBorderWidth, rowBorderColor),
        modifier = Modifier.fillMaxWidth().clickable { onToggle() }
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                "$number",
                style = TextStyle(color = MUTED.copy(alpha = 0.5f), fontSize = 11.sp, fontFamily = FontFamily.Monospace),
                modifier = Modifier.width(16.dp)
            )
            Box(Modifier.size(6.dp).clip(RoundedCornerShape(3.dp)).background(statusColor))
            // Name + NIM
            Column(Modifier.weight(1f)) {
                Text(
                    student.nama.ifBlank { "(tanpa nama)" },
                    style = TextStyle(
                        color = if (isSelected) GREEN else if (isDone) MUTED.copy(alpha = 0.4f) else Color.White,
                        fontSize = 12.sp,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (student.nim.isNotBlank() && student.nama.isNotBlank()) {
                    Text(
                        student.nim,
                        style = TextStyle(color = MUTED.copy(alpha = 0.5f), fontSize = 8.sp, fontFamily = FontFamily.Monospace),
                        maxLines = 1
                    )
                }
            }
            // Selection checkbox icon (matches Electron mc-panel:
            // CheckSquare when selected, Square when not).
            Icon(
                imageVector = if (isSelected) Icons.Default.CheckBox else Icons.Default.CheckBoxOutlineBlank,
                contentDescription = if (isSelected) "Hapus pilihan" else "Pilih",
                modifier = Modifier.size(14.dp),
                tint = if (isSelected) GREEN else MUTED.copy(alpha = 0.5f)
            )
        }
    }
}

@Composable
private fun MCQueueRow(number: Int, student: Student, channel: Int) {
    val isActive = isActiveStatus(student.status)
    val isDone = student.status == "done"
    // HIGHLIGHT: first pending row (next to call) → button-like gold (matches
    // the React panels' isNext). number==1 + pending = top of the queue.
    val isFirstPending = number == 1 && student.status == "pending"
    val statusColor = when {
        isActive -> GOLD
        isDone -> GREEN.copy(alpha = 0.5f)
        else -> MUTED
    }
    val rowBg = when {
        isActive -> GOLD.copy(alpha = 0.18f)
        isFirstPending -> GOLD.copy(alpha = 0.1f)
        isDone -> PANEL.copy(alpha = 0.3f)
        else -> PANEL
    }
    val rowBorderWidth = if (isActive || isFirstPending) 1.5.dp else 1.dp
    val rowBorderColor = when {
        isActive -> GOLD
        isFirstPending -> GOLD.copy(alpha = 0.7f)
        else -> BORDER.copy(alpha = 0.2f)
    }
    Card(colors = CardDefaults.cardColors(containerColor = rowBg), shape = RoundedCornerShape(6.dp),
        border = androidx.compose.foundation.BorderStroke(rowBorderWidth, rowBorderColor)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("$number", style = TextStyle(color = MUTED.copy(alpha = 0.5f), fontSize = 11.sp, fontFamily = FontFamily.Monospace), modifier = Modifier.width(16.dp))
            Box(Modifier.size(6.dp).clip(RoundedCornerShape(3.dp)).background(statusColor))
            // Name + NIM (matches the Electron mc-panel + saatiril-mc.apk — shows
            // BOTH name + NIM, with NIM as monospace secondary text).
            Column(Modifier.weight(1f)) {
                Text(student.nama.ifBlank { "(tanpa nama)" }, style = TextStyle(color = if (isActive || isFirstPending) GOLD else if (isDone) MUTED.copy(alpha = 0.4f) else Color.White, fontSize = 12.sp, fontWeight = if (isActive) FontWeight.Bold else if (isFirstPending) FontWeight.SemiBold else FontWeight.Normal), maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (student.nim.isNotBlank() && student.nama.isNotBlank()) {
                    Text(student.nim, style = TextStyle(color = MUTED.copy(alpha = 0.5f), fontSize = 8.sp, fontFamily = FontFamily.Monospace), maxLines = 1)
                }
            }
            if (isActive) Text("◆", style = TextStyle(color = GOLD, fontSize = 10.sp, fontWeight = FontWeight.Bold))
            else if (isFirstPending) Text("▶", style = TextStyle(color = GOLD, fontSize = 10.sp, fontWeight = FontWeight.Bold))
            if (isDone) Text("✓", style = TextStyle(color = GREEN, fontSize = 10.sp))
        }
    }
}
