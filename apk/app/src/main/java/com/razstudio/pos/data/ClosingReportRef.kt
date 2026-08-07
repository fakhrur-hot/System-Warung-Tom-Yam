package com.razstudio.pos.data

/**
 * A generated closing report, and where to fetch it from.
 *
 * [url] is a short-lived signed link (the backend mints it for an hour), so it is something to
 * download now rather than something to keep. [date] is the **business day** the report covers,
 * which is not necessarily today's calendar date — a café closing at 2 AM is still closing the day
 * it opened, and the file it saves has to say so.
 */
data class ClosingReportRef(
    val url: String,
    val date: String,
)
