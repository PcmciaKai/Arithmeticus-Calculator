package com.vortessence.arithmeticus.ui.dialogs

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.vortessence.arithmeticus.R
import com.vortessence.arithmeticus.core.math.MathEngine
import com.vortessence.arithmeticus.core.math.context.MathContext
import com.vortessence.arithmeticus.core.math.type.MathString
import com.vortessence.arithmeticus.core.math.type.ResultForm
import com.vortessence.arithmeticus.core.math.type.ResultFormatter
import com.vortessence.arithmeticus.model.help.HelpBlock
import com.vortessence.arithmeticus.model.help.HelpDb
import com.vortessence.arithmeticus.model.help.HelpSection
import com.vortessence.arithmeticus.model.help.HelpTopic
import com.vortessence.arithmeticus.ui.math.DecimalMark
import com.vortessence.arithmeticus.ui.math.MathExpressionView
import java.util.Locale

/** The widest the text of the help gets on a tablet: longer lines are hard to read. */
private val MAX_CONTENT_WIDTH = 720.dp

/** The column of the keys on a help page, so that what they do starts at the same place on every row; longer labels wrap. */
private val KEY_LABEL_WIDTH = 96.dp

/**
 * The in-app help, over the whole screen: a searchable list of topics (how the app is used, then the functions), and a page for
 * each. Back goes from a page to the list, and from the list out of the help. Opened right on the page [initialTopic] (from the
 * function picker), back leaves the help from there. [onTryExample] puts an example into the line.
 */
@Composable
fun HelpDialog(initialTopic: String? = null, onTryExample: (MathString) -> Unit, onDismiss: () -> Unit) {
    val startTopic = remember(initialTopic) { initialTopic?.let(HelpDb::topic) }
    var openTopic by remember(startTopic) { mutableStateOf(startTopic) }
    var searchQuery by remember { mutableStateOf("") }
    // Kept out here, so the list is where it was when a page is left
    val listState = rememberLazyListState()
    val hideKeyboard = rememberHideKeyboard()
    val back = { if (openTopic != null && startTopic == null) openTopic = null else onDismiss() }

    Dialog(onDismissRequest = back, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(modifier = Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = back) {
                        if (openTopic != null) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.help_back))
                        } else {
                            Icon(Icons.Default.Close, contentDescription = stringResource(R.string.common_close))
                        }
                    }
                    Text(
                        text = stringResource(openTopic?.title ?: R.string.help_title),
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f)
                    )
                }
                val contentModifier = Modifier.widthIn(max = MAX_CONTENT_WIDTH).fillMaxWidth().weight(1f)
                val topic = openTopic
                if (topic == null) {
                    TopicList(
                        searchQuery = searchQuery,
                        onSearchChange = { searchQuery = it },
                        listState = listState,
                        onOpen = { hideKeyboard(); openTopic = it },
                        modifier = contentModifier
                    )
                } else {
                    TopicPage(topic, onTryExample = { hideKeyboard(); onTryExample(it) }, modifier = contentModifier)
                }
            }
        }
    }
}

@Composable
private fun TopicList(
    searchQuery: String,
    onSearchChange: (String) -> Unit,
    listState: LazyListState,
    onOpen: (HelpTopic) -> Unit,
    modifier: Modifier
) {
    val resources = LocalResources.current
    val grouped = remember(searchQuery, resources) {
        HelpDb.topics.filter { topic -> searchQuery.isBlank() || searchableText(topic) { resources.getString(it) }.contains(searchQuery.trim(), ignoreCase = true) }
            .groupBy { it.section }
    }
    LazyColumn(
        state = listState,
        modifier = modifier,
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        item(key = "search") {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = onSearchChange,
                placeholder = { Text(stringResource(R.string.help_search)) },
                modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
                shape = RoundedCornerShape(12.dp),
                singleLine = true
            )
        }
        if (searchQuery.isBlank()) {
            item(key = "intro") {
                Text(
                    text = stringResource(R.string.help_intro),
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                )
            }
        }
        if (grouped.isEmpty()) {
            item(key = "none") {
                Text(
                    text = stringResource(R.string.help_no_results),
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(4.dp)
                )
            }
        }
        HelpSection.entries.forEach { section ->
            val topics = grouped[section] ?: return@forEach
            item(key = section.name) {
                Text(
                    text = stringResource(section.title),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = 4.dp, top = 10.dp, bottom = 2.dp)
                )
            }
            items(topics, key = { it.id }) { topic ->
                TopicRow(topic, onClick = { onOpen(topic) })
            }
        }
    }
}

/** Everything a search can find a topic by: its title, its summary and the text of its page, math included. */
private fun searchableText(topic: HelpTopic, string: (Int) -> String): String = buildList {
    add(string(topic.title))
    add(string(topic.summary))
    topic.blocks.forEach { block ->
        when (block) {
            is HelpBlock.Paragraph -> add(string(block.text))
            is HelpBlock.Heading -> add(string(block.text))
            is HelpBlock.Key -> { add(block.label ?: string(block.labelRes!!)); add(string(block.text)) }
            is HelpBlock.Syntax -> { add(block.form); add(string(block.text)) }
            is HelpBlock.Example -> { add(block.input); add(block.result); block.note?.let { add(string(it)) } }
        }
    }
}.joinToString("\n")

@Composable
private fun TopicRow(topic: HelpTopic, onClick: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
    ) {
        Row(
            modifier = Modifier.padding(start = 14.dp, end = 8.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(stringResource(topic.title), fontSize = 15.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurface)
                Text(stringResource(topic.summary), fontSize = 12.sp, lineHeight = 15.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun TopicPage(topic: HelpTopic, onTryExample: (MathString) -> Unit, modifier: Modifier) {
    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item(key = "summary") {
            Text(stringResource(topic.summary), fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        items(topic.blocks) { block ->
            when (block) {
                is HelpBlock.Paragraph -> Text(stringResource(block.text), fontSize = 15.sp, lineHeight = 22.sp)
                is HelpBlock.Heading -> Text(
                    text = stringResource(block.text),
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 8.dp)
                )
                // A label of the app (Fkt.) is shown as it is; only the keys of the help are math with a decimal point
                is HelpBlock.Key -> KeyRow(block.label?.let(::shown) ?: stringResource(block.labelRes!!), stringResource(block.text))
                is HelpBlock.Syntax -> SyntaxRow(block)
                is HelpBlock.Example -> ExampleCard(block, onTryExample)
            }
        }
    }
}

/** Math to type as it is shown: with a decimal comma, the arguments are separated by "; " and 1.5 is 1,5. */
private fun shown(math: String): String = DecimalMark.display(math.replace(", ", DecimalMark.argumentSeparator))

/** A result as it is shown: only the decimal mark changes, the ", " between solutions (x = 3, x = 2) stays. */
private fun shownResult(math: String): String = DecimalMark.display(math)

/** A key, drawn somewhat like it is on the keypad, next to what it does. */
@Composable
private fun KeyRow(label: String, text: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
        Surface(
            shape = RoundedCornerShape(8.dp),
            color = MaterialTheme.colorScheme.secondaryContainer,
            modifier = Modifier.width(KEY_LABEL_WIDTH)
        ) {
            Text(
                text = label,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp)
            )
        }
        Text(text, fontSize = 14.sp, lineHeight = 20.sp, modifier = Modifier.weight(1f).padding(top = 4.dp))
    }
}

@Composable
private fun SyntaxRow(syntax: HelpBlock.Syntax) {
    val form = syntax.word?.let { String.format(Locale.ROOT, syntax.form, stringResource(it)) } ?: syntax.form
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = shown(form),
            fontSize = 17.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface
        )
        Text(stringResource(syntax.text), fontSize = 14.sp, lineHeight = 20.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/**
 * What to type, and what the result line shows for it. An example with its math is drawn as the line draws it and calculated
 * here, with the result as the line shows it; the button puts it into the line.
 */
@Composable
private fun ExampleCard(example: HelpBlock.Example, onTry: (MathString) -> Unit) {
    val math = remember(example) { example.math }
    val result by produceState<MathString?>(null, math) {
        if (math == null || example.result.isEmpty()) return@produceState
        val calculated = MathEngine.calculate(math, MathContext(complexNumbers = true))
        if (calculated.isSuccess) value = ResultFormatter.format(calculated, ResultForm.EXACT)
    }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
    ) {
        Column(modifier = Modifier.padding(start = 14.dp, end = 14.dp, top = 10.dp, bottom = 4.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            if (math != null) {
                Box(Modifier.horizontalScroll(rememberScrollState())) {
                    MathExpressionView(mathString = math, fontSize = 20.sp, textColor = MaterialTheme.colorScheme.onSurface)
                }
            } else {
                Text(shown(example.input), fontSize = 18.sp, color = MaterialTheme.colorScheme.onSurface)
            }
            if (example.result.isNotEmpty()) {
                // As the result line has it, also for a solution: = x = 2
                Row(
                    modifier = Modifier.align(Alignment.End).horizontalScroll(rememberScrollState()),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("= ", fontSize = 20.sp, fontWeight = FontWeight.Light, color = MaterialTheme.colorScheme.primary)
                    val shownMath = result
                    if (shownMath != null) {
                        MathExpressionView(mathString = shownMath, fontSize = 20.sp, textColor = MaterialTheme.colorScheme.onSurface)
                    } else {
                        Text(shownResult(example.result), fontSize = 18.sp, color = MaterialTheme.colorScheme.onSurface)
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) {
                    example.note?.let {
                        Text(stringResource(it), fontSize = 12.sp, lineHeight = 16.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                if (math != null) {
                    val description = stringResource(R.string.help_try_description, example.input)
                    TextButton(
                        onClick = { onTry(math) },
                        modifier = Modifier.semantics { contentDescription = description }
                    ) {
                        Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(stringResource(R.string.help_try))
                    }
                }
            }
            if (math == null) Spacer(Modifier.height(6.dp))
        }
    }
}
