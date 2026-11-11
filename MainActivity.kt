package ee.alfred.text

import android.app.Activity
import android.app.AlertDialog
import android.content.res.ColorStateList
import android.content.ActivityNotFoundException
import android.content.Intent
import android.database.Cursor
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.text.Editable
import android.text.InputFilter
import android.text.InputType
import android.text.TextUtils
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import java.io.File
import java.io.IOException
import java.util.concurrent.Executors

class MainActivity : Activity() {
    private val mainHandler = Handler(Looper.getMainLooper())
    private lateinit var editor: EditText
    private lateinit var fileName: TextView
    private lateinit var status: TextView
    private lateinit var editorCharacterCount: TextView
    private lateinit var actionButtons: List<Button>
    private var currentDocumentUri: Uri? = null
    private var permissionWarning = false
    private var isRestoringDraft = false
    private var isFileOperationInProgress = false
    private var pendingDraftSave: Runnable? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE
        )

        currentDocumentUri = savedInstanceState
            ?.getString(STATE_DOCUMENT_URI)
            ?.let(Uri::parse)

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(24), dp(20), dp(16))
            setBackgroundColor(BACKGROUND)
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(TextView(this).apply {
            text = ">"
            textSize = 30f
            typeface = Typeface.MONOSPACE
            gravity = Gravity.CENTER
            setTextColor(BACKGROUND)
            background = GradientDrawable().apply {
                setColor(TERMINAL_GREEN)
                cornerRadius = dp(8).toFloat()
            }
        }, LinearLayout.LayoutParams(dp(48), dp(48)))

        val heading = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), 0, 0, 0)
        }
        heading.addView(TextView(this).apply {
            text = getString(R.string.terminal_title)
            textSize = 20f
            typeface = Typeface.MONOSPACE
            setTextColor(TERMINAL_GREEN)
        })
        heading.addView(TextView(this).apply {
            text = getString(R.string.terminal_subtitle)
            textSize = 11f
            typeface = Typeface.MONOSPACE
            setTextColor(DIM_GREEN)
            setPadding(0, dp(3), 0, 0)
        })
        header.addView(
            heading,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        )
        content.addView(header)

        content.addView(TextView(this).apply {
            text = getString(R.string.workspace_label)
            textSize = 11f
            typeface = Typeface.MONOSPACE
            setTextColor(DIM_GREEN)
            setPadding(0, dp(24), 0, dp(8))
        })

        val fileCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(8))
            background = panelBackground()
        }
        fileCard.addView(TextView(this).apply {
            text = getString(R.string.current_file_label)
            textSize = 10f
            typeface = Typeface.MONOSPACE
            setTextColor(DIM_GREEN)
        })
        fileName = TextView(this).apply {
            text = savedInstanceState?.getString(STATE_DOCUMENT_NAME)
                ?: getString(R.string.new_document)
            textSize = 14f
            typeface = Typeface.MONOSPACE
            setTextColor(TERMINAL_GREEN)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.MIDDLE
            setPadding(0, dp(4), 0, dp(2))
        }
        fileCard.addView(fileName)
        content.addView(fileCard)

        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(12), 0, dp(4))
        }
        val openButton = actionButton(R.string.open_file, isPrimary = true) { openDocument() }
        val saveButton = actionButton(R.string.save_file) { saveDocument() }
        val clearButton = actionButton(R.string.clear_all, isDestructive = true) {
            confirmClearAll()
        }
        actionButtons = listOf(openButton, saveButton, clearButton)
        actions.addView(openButton, weightedParams())
        actions.addView(View(this), LinearLayout.LayoutParams(dp(10), 1))
        actions.addView(saveButton, weightedParams())
        content.addView(actions)
        clearButton.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            dp(44)
        )
        content.addView(clearButton)

        editor = EditText(this).apply {
            hint = getString(R.string.editor_hint)
            gravity = Gravity.TOP or Gravity.START
            inputType = InputType.TYPE_CLASS_TEXT or
                InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            imeOptions = EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
            setHorizontallyScrolling(false)
            typeface = Typeface.MONOSPACE
            textSize = 16f
            setTextColor(TERMINAL_GREEN)
            setHintTextColor(DIM_GREEN)
            minLines = 8
            filters = arrayOf(InputFilter.LengthFilter(TextDocumentStorage.MAX_DOCUMENT_CHARACTERS))
            isSaveEnabled = false
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
            isVerticalScrollBarEnabled = true
            setPadding(dp(12), dp(12), dp(12), dp(12))
            background = panelBackground()
        }
        editor.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(text: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(text: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(text: Editable?) {
                updateEditorCharacterCount(text?.length ?: 0)
                scheduleDraftSave()
            }
        })
        content.addView(
            editor,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        )

        val editorInfo = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(2), dp(6), 0, 0)
        }
        editorInfo.addView(TextView(this).apply {
            text = getString(R.string.editor_footer)
            textSize = 10f
            typeface = Typeface.MONOSPACE
            setTextColor(DIM_GREEN)
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        editorCharacterCount = TextView(this).apply {
            textSize = 10f
            typeface = Typeface.MONOSPACE
            setTextColor(DIM_GREEN)
            gravity = Gravity.END
        }
        editorInfo.addView(editorCharacterCount)
        content.addView(editorInfo)

        status = TextView(this).apply {
            text = getString(R.string.ready)
            textSize = 11f
            typeface = Typeface.MONOSPACE
            setTextColor(DIM_GREEN)
            setPadding(0, dp(12), 0, dp(5))
        }
        content.addView(status)

        content.addView(View(this).apply {
            setBackgroundColor(PANEL_BORDER)
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1)).apply {
            topMargin = dp(4)
        })
        content.addView(TextView(this).apply {
            text = getString(R.string.developer_credit)
            textSize = 10f
            typeface = Typeface.MONOSPACE
            gravity = Gravity.CENTER
            setTextColor(DIM_GREEN)
            setPadding(0, dp(10), 0, 0)
        })

        setContentView(content)
        val retainedState = lastNonConfigurationInstance as? RetainedEditorState
        if (retainedState != null && !retainedState.restoringDraft) {
            editor.setText(retainedState.text)
        } else {
            restoreDraft()
        }
        if (savedInstanceState != null) {
            fileName.text = savedInstanceState.getString(
                STATE_DOCUMENT_NAME,
                getString(R.string.new_document)
            )
        }
        updateEditorCharacterCount(editor.text.length)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString(STATE_DOCUMENT_URI, currentDocumentUri?.toString())
        outState.putString(STATE_DOCUMENT_NAME, fileName.text.toString())
        super.onSaveInstanceState(outState)
    }

    @Deprecated("Deprecated in Android, retained for configuration-change restoration")
    override fun onRetainNonConfigurationInstance(): Any =
        RetainedEditorState(editor.text.toString(), isRestoringDraft)

    override fun onPause() {
        pendingDraftSave?.let(mainHandler::removeCallbacks)
        pendingDraftSave = null
        if (::editor.isInitialized && !isRestoringDraft) {
            enqueueDraftSave(editor.text.toString())
        }
        super.onPause()
    }

    private fun scheduleDraftSave() {
        if (isRestoringDraft || !::editor.isInitialized) return
        pendingDraftSave?.let(mainHandler::removeCallbacks)
        pendingDraftSave = Runnable {
            pendingDraftSave = null
            if (!isFinishing && !isDestroyed && !isRestoringDraft) {
                enqueueDraftSave(editor.text.toString())
            }
        }.also { mainHandler.postDelayed(it, DRAFT_SAVE_DELAY_MILLIS) }
    }

    private fun enqueueDraftSave(text: String) {
        IO_EXECUTOR.execute {
            try {
                TextDocumentStorage.writeDraft(File(filesDir, DRAFT_FILE_NAME), text)
            } catch (error: IOException) {
                val message = error.message ?: error.javaClass.simpleName
                mainHandler.post {
                    if (!isFinishing && !isDestroyed) {
                        showStatus(R.string.file_error, message)
                    }
                }
            }
        }
    }

    private fun restoreDraft() {
        isRestoringDraft = true
        editor.isEnabled = false
        actionButtons.forEach { it.isEnabled = false }
        showStatus(R.string.restoring)
        IO_EXECUTOR.execute {
            var text: String? = null
            var failure: String? = null
            try {
                text = TextDocumentStorage.readDraft(File(filesDir, DRAFT_FILE_NAME))
            } catch (error: IOException) {
                failure = error.message ?: error.javaClass.simpleName
            }
            mainHandler.post {
                if (isFinishing || isDestroyed) return@post
                isRestoringDraft = false
                editor.isEnabled = true
                actionButtons.forEach { it.isEnabled = true }
                if (failure != null) {
                    showStatus(R.string.file_error, failure)
                } else {
                    if (text != null) editor.setText(text)
                    showStatus(if (text == null) R.string.ready else R.string.draft_restored)
                }
            }
        }
    }

    @Deprecated("Deprecated in Android, retained for compatibility with the min SDK")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_OPEN && requestCode != REQUEST_CREATE) return
        if (resultCode != RESULT_OK) {
            finishFileOperation()
            showStatus(R.string.cancelled)
            return
        }

        val uri = data?.data
        if (uri == null) {
            finishFileOperation()
            showStatus(R.string.file_error, getString(R.string.missing_file))
            return
        }
        retainUriPermission(uri, data.flags)

        when (requestCode) {
            REQUEST_OPEN -> readDocument(uri)
            REQUEST_CREATE -> writeDocument(uri)
            else -> finishFileOperation()
        }
    }

    private fun openDocument() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "text/*"
            addFlags(
                Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                    Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
            )
        }
        launchPicker(intent, REQUEST_OPEN, R.string.opening)
    }

    private fun saveDocument() {
        val uri = currentDocumentUri
        if (uri != null) {
            beginFileOperation(R.string.saving)
            writeDocument(uri)
            return
        }

        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "text/plain"
            putExtra(Intent.EXTRA_TITLE, getString(R.string.default_file_name))
            addFlags(
                Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                    Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
            )
        }
        launchPicker(intent, REQUEST_CREATE, R.string.saving)
    }

    private fun confirmClearAll() {
        AlertDialog.Builder(this)
            .setTitle(R.string.clear_all_title)
            .setMessage(R.string.clear_all_confirmation)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.clear_all) { _, _ ->
                editor.text.clear()
                showStatus(R.string.cleared)
            }
            .show()
    }

    private fun launchPicker(intent: Intent, requestCode: Int, progressMessage: Int) {
        if (isFileOperationInProgress) return
        beginFileOperation(progressMessage)
        try {
            startActivityForResult(intent, requestCode)
        } catch (_: ActivityNotFoundException) {
            finishFileOperation()
            showStatus(R.string.file_error, getString(R.string.no_file_picker))
        }
    }

    private fun readDocument(uri: Uri) {
        IO_EXECUTOR.execute {
            var text: String? = null
            var name: String? = null
            var failure: String? = null
            try {
                val input = contentResolver.openInputStream(uri)
                    ?: throw IOException(getString(R.string.could_not_open_stream))
                text = input.use(TextDocumentStorage::read)
                name = displayName(uri)
            } catch (error: IOException) {
                failure = error.message ?: error.javaClass.simpleName
            } catch (error: SecurityException) {
                failure = error.message ?: error.javaClass.simpleName
            }
            mainHandler.post {
                if (isFinishing || isDestroyed) return@post
                finishFileOperation()
                if (failure != null) {
                    showStatus(R.string.file_error, failure)
                } else if (text != null && name != null) {
                    editor.setText(text)
                    currentDocumentUri = uri
                    fileName.text = name
                    showOperationComplete(R.string.opened)
                }
            }
        }
    }

    private fun writeDocument(uri: Uri) {
        val text = editor.text.toString()
        IO_EXECUTOR.execute {
            var name: String? = null
            var failure: String? = null
            try {
                val bytes = TextDocumentStorage.encode(text)
                val output = contentResolver.openOutputStream(uri, "wt")
                    ?: throw IOException(getString(R.string.could_not_open_stream))
                output.use {
                    it.write(bytes)
                    it.flush()
                }
                name = displayName(uri)
            } catch (error: IOException) {
                failure = error.message ?: error.javaClass.simpleName
            } catch (error: SecurityException) {
                failure = error.message ?: error.javaClass.simpleName
            }
            mainHandler.post {
                if (isFinishing || isDestroyed) return@post
                finishFileOperation()
                if (failure != null) {
                    showStatus(R.string.file_error, failure)
                } else if (name != null) {
                    currentDocumentUri = uri
                    fileName.text = name
                    showOperationComplete(R.string.saved)
                }
            }
        }
    }

    private fun retainUriPermission(uri: Uri, resultFlags: Int) {
        permissionWarning = false
        val accessFlags = resultFlags and
            (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        if (resultFlags and Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION != 0 && accessFlags != 0) {
            try {
                contentResolver.takePersistableUriPermission(uri, accessFlags)
            } catch (_: SecurityException) {
                permissionWarning = true
            }
        }
    }

    private fun displayName(uri: Uri): String {
        var cursor: Cursor? = null
        try {
            cursor = contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            if (cursor != null && cursor.moveToFirst()) {
                val column = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (column >= 0) return cursor.getString(column) ?: uri.lastPathSegment.orEmpty()
            }
        } catch (_: IllegalArgumentException) {
            return uri.lastPathSegment ?: getString(R.string.unknown_file)
        } finally {
            cursor?.close()
        }
        return uri.lastPathSegment ?: getString(R.string.unknown_file)
    }

    private fun actionButton(
        label: Int,
        isPrimary: Boolean = false,
        isDestructive: Boolean = false,
        action: () -> Unit
    ) = Button(this).apply {
        text = getString(label)
        textSize = 13f
        typeface = Typeface.MONOSPACE
        setTextColor(
            when {
                isDestructive -> DESTRUCTIVE_RED
                isPrimary -> BACKGROUND
                else -> TERMINAL_GREEN
            }
        )
        isAllCaps = false
        minHeight = dp(48)
        minWidth = 0
        setPadding(dp(6), 0, dp(6), 0)
        val strokeColor = when {
            isDestructive -> DESTRUCTIVE_RED
            isPrimary -> TERMINAL_GREEN
            else -> PANEL_BORDER
        }
        background = RippleDrawable(
            ColorStateList.valueOf(if (isDestructive) DESTRUCTIVE_RED else TERMINAL_GREEN),
            GradientDrawable().apply {
                setColor(if (isPrimary) TERMINAL_GREEN else BUTTON_BACKGROUND)
                setStroke(dp(1), strokeColor)
                cornerRadius = dp(8).toFloat()
            },
            null
        )
        contentDescription = getString(label)
        setOnClickListener {
            if (isEnabled) action()
        }
    }

    private fun panelBackground() = GradientDrawable().apply {
        setColor(PANEL_BACKGROUND)
        setStroke(dp(1), PANEL_BORDER)
        cornerRadius = dp(8).toFloat()
    }

    private fun weightedParams() = LinearLayout.LayoutParams(0, dp(48), 1f)

    private fun updateEditorCharacterCount(count: Int) {
        editorCharacterCount.text = getString(
            R.string.editor_character_count,
            count,
            TextDocumentStorage.MAX_DOCUMENT_CHARACTERS
        )
    }

    private fun beginFileOperation(progressMessage: Int) {
        if (isFileOperationInProgress) return
        isFileOperationInProgress = true
        editor.isEnabled = false
        actionButtons.forEach { it.isEnabled = false }
        showStatus(progressMessage)
    }

    private fun finishFileOperation() {
        isFileOperationInProgress = false
        if (!isRestoringDraft) editor.isEnabled = true
        actionButtons.forEach { it.isEnabled = !isRestoringDraft }
    }

    private fun showStatus(message: Int, vararg formatArgs: Any) {
        status.text = getString(message, *formatArgs)
        status.setTextColor(
            when (message) {
                R.string.file_error -> ERROR_RED
                R.string.permission_not_remembered -> WARNING_YELLOW
                else -> DIM_GREEN
            }
        )
    }

    private fun showOperationComplete(message: Int) {
        showStatus(if (permissionWarning) R.string.permission_not_remembered else message)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private companion object {
        val IO_EXECUTOR = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "alfred-text-io").apply { isDaemon = true }
        }

        const val REQUEST_OPEN = 10
        const val REQUEST_CREATE = 11
        const val STATE_DOCUMENT_URI = "document_uri"
        const val STATE_DOCUMENT_NAME = "document_name"
        const val DRAFT_FILE_NAME = "draft.txt"
        const val DRAFT_SAVE_DELAY_MILLIS = 750L

        const val BACKGROUND = 0xFF000000.toInt()
        const val PANEL_BACKGROUND = 0xFF030804.toInt()
        const val BUTTON_BACKGROUND = 0xFF080F09.toInt()
        const val PANEL_BORDER = 0xFF25452D.toInt()
        const val TERMINAL_GREEN = 0xFF73FF8B.toInt()
        const val DIM_GREEN = 0xFF4F9B5B.toInt()
        const val DESTRUCTIVE_RED = 0xFFFF7777.toInt()
        const val WARNING_YELLOW = 0xFFFFD166.toInt()
        const val ERROR_RED = 0xFFFF7777.toInt()
    }
}

private data class RetainedEditorState(val text: String, val restoringDraft: Boolean)
