package org.zotero.android.screens.allitems

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import org.zotero.android.appupdate.MaybeShowAppUpdateBanner
import org.zotero.android.architecture.ui.CustomLayoutSize
import org.zotero.android.screens.allitems.bottomsheet.AllItemsAddBottomSheet
import org.zotero.android.screens.allitems.table.AllItemsTable
import org.zotero.android.screens.share.ShareActivity
import org.zotero.android.uicomponents.CustomScaffoldM3
import org.zotero.android.uicomponents.Strings
import org.zotero.android.uicomponents.error.FullScreenError
import org.zotero.android.uicomponents.loading.BaseLceBox
import org.zotero.android.uicomponents.loading.CircularLoading
import org.zotero.android.uicomponents.themem3.AppThemeM3
import java.io.File

@Composable
internal fun AllItemsScreen(
    viewModel: AllItemsViewModel = hiltViewModel(),
    onPickFile: () -> Unit,
    onOpenFile: (file: File, mimeType: String) -> Unit,
    onOpenWebpage: (url: String) -> Unit,
    navigateToCollectionsScreen: (String) -> Unit,
    navigateToSinglePicker: () -> Unit,
    navigateToAddByIdentifier: (addByIdentifierParams: String) -> Unit,
    navigateToRetrieveMetadata: (params: String) -> Unit,
    navigateToAllItemsSort: () -> Unit,
    navigateToCollectionPicker: () -> Unit,
    navigateToItemDetails: (String) -> Unit,
    navigateToAddOrEditNote: (String) -> Unit,
    navigateToVideoPlayerScreen: () -> Unit,
    navigateToImageViewerScreen: () -> Unit,
    navigateToZoterWebViewScreen: (String) -> Unit,
    navigateToTagFilter: (params: String) -> Unit,
    navigateToScanBarcode: () -> Unit,
    navigateToSingleCitation: () -> Unit,
    navigateToCitationBibliographyExport:() -> Unit,
    onShowPdf: (String, String) -> Unit,
    onShowHtmlOrEpub: (String) -> Unit,
) {
    AppThemeM3 {
        val context = LocalContext.current
        val layoutType = CustomLayoutSize.calculateLayoutType()
        val viewState by viewModel.viewStates.observeAsState(AllItemsViewState())
        val viewEffect by viewModel.viewEffects.observeAsState()
        val lazyListState = rememberLazyListState()

        var showUrlDialog by remember { mutableStateOf(false) }
        var urlText by remember { mutableStateOf("") }

        val isTablet = layoutType.isTablet()

        if (!isTablet)  {
            BackHandler(onBack = {
                viewModel.navigateToCollections()
            })
        }

        LaunchedEffect(key1 = viewModel) {
            viewModel.init(isTablet)
        }

        LaunchedEffect(key1 = viewEffect) {
            when (val consumedEffect = viewEffect?.consume()) {
                null -> Unit
                is AllItemsViewEffect.ShowCollectionsEffect -> navigateToCollectionsScreen(
                    consumedEffect.screenArgs
                )

                is AllItemsViewEffect.ShowItemDetailEffect -> navigateToItemDetails(consumedEffect.screenArgs)
                is AllItemsViewEffect.ShowAddOrEditNoteEffect -> navigateToAddOrEditNote(
                    consumedEffect.screenArgs
                )

                is AllItemsViewEffect.ShowPhoneFilterEffect -> {
                    navigateToTagFilter(consumedEffect.params)
                }

                AllItemsViewEffect.ShowItemTypePickerEffect -> {
                    navigateToSinglePicker()
                }

                is AllItemsViewEffect.ShowAddByIdentifierEffect -> {
                    navigateToAddByIdentifier(consumedEffect.params)
                }

                is AllItemsViewEffect.ShowRetrieveMetadataDialogEffect -> {
                    navigateToRetrieveMetadata(consumedEffect.params)
                }

                AllItemsViewEffect.ShowSortPickerEffect -> {
                    navigateToAllItemsSort()
                }

                AllItemsViewEffect.ShowCollectionPickerEffect -> {
                    navigateToCollectionPicker()
                }

                AllItemsViewEffect.ScreenRefresh -> {
                    //no-op
                }

                is AllItemsViewEffect.OpenFile -> onOpenFile(
                    consumedEffect.file,
                    consumedEffect.mimeType
                )

                is AllItemsViewEffect.OpenWebpage -> onOpenWebpage(consumedEffect.url)
                is AllItemsViewEffect.NavigateToPdfScreen -> {
                    onShowPdf(consumedEffect.params, consumedEffect.encodedFilePath)
                }
                is AllItemsViewEffect.NavigateToHtmlEpubReaderScreen -> {
                    onShowHtmlOrEpub(consumedEffect.params)
                }

                is AllItemsViewEffect.ShowVideoPlayer -> {
                    navigateToVideoPlayerScreen()
                }

                is AllItemsViewEffect.ShowImageViewer -> {
                    navigateToImageViewerScreen()
                }

                is AllItemsViewEffect.ShowZoteroWebView -> {
                    navigateToZoterWebViewScreen(consumedEffect.url)
                }

                is AllItemsViewEffect.ShowScanBarcode -> {
                    navigateToScanBarcode()
                }

                is AllItemsViewEffect.MaybeScrollToTop -> {
                    val maybeIndex =
                        lazyListState.layoutInfo.visibleItemsInfo.firstOrNull()?.index
                    //Indices of the first visible item AFTER an update, so after the potential new item was added to the top.
                    if (((1..2).contains(maybeIndex))) {
                        lazyListState.scrollToItem(index = 0, scrollOffset = 0)
                    }
                }

                AllItemsViewEffect.ShowSingleCitationEffect -> {
                    navigateToSingleCitation()
                }

                AllItemsViewEffect.ShowCitationBibliographyExportEffect -> {
                    navigateToCitationBibliographyExport()
                }

                AllItemsViewEffect.ShowAddFromUrlDialogEffect -> {
                    urlText = ""
                    showUrlDialog = true
                }

                is AllItemsViewEffect.LaunchShareActivityEffect -> {
                    val shareIntent = Intent().apply {
                        putExtra(Intent.EXTRA_TEXT, consumedEffect.url)
                    }
                    context.startActivity(ShareActivity.getIntent(shareIntent, context))
                }
            }
        }
        val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()

        CustomScaffoldM3(
            scrollBehavior = scrollBehavior,
            topBar = {
                AllItemsTopBar(
                    scrollBehavior = scrollBehavior,
                    viewState = viewState,
                    viewModel = viewModel,
                    layoutType = layoutType,
                )
            },
            bottomBar = {
                AllItemsBottomPanelNew(
                    viewModel = viewModel,
                    viewState = viewState,
                )
            },
        ) {

            BaseLceBox(
                modifier = Modifier.fillMaxSize(),
                lce = viewState.lce,
                error = { _ ->
                    FullScreenError(
                        modifier = Modifier.align(Alignment.Center),
                        errorTitle = stringResource(id = Strings.error_list_load_check_crash_logs),
                    )
                },
                loading = {
                    CircularLoading()
                },
            ) {
                AllItemsTable(
                    lazyListState = lazyListState,
                    itemCellModels = viewState.itemCellModels,
                    isEditing = viewState.isEditing,
                    isRefreshing = viewState.isRefreshing,
                    isItemSelected = viewState::isSelected,
                    getItemAccessory = viewState::getAccessoryForItem,
                    onItemTapped = viewModel::onItemTapped,
                    onAccessoryTapped = viewModel::onAccessoryTapped,
                    onItemLongTapped = viewModel::onItemLongTapped,
                    onStartSync = viewModel::startSync
                )

                MaybeShowAppUpdateBanner(
                    appUpdateBannerPayload = viewState.appUpdateBannerPayload,
                    shouldShowAppUpdateBanner = viewState.shouldShowAppUpdateBanner,
                    onDownloadButtonTapped = viewModel::onAppUpdateDownloadButtonTapped,
                    onLaterButtonTapped = viewModel::onAppUpdateLaterButtonTapped
                )

                val itemsError = viewState.error
                if (itemsError != null) {
                    ShowErrorOrDialog(
                        itemsError = itemsError,
                        onDismissDialog = viewModel::onDismissDialog,
                        onDeleteItems = { viewModel.delete(it) },
                        onEmptyTrash = { viewModel.emptyTrash() },
                        deleteItemsFromCollection = { viewModel.deleteItemsFromCollection(it) },
                    )
                }

                if (viewState.isGeneratingBibliography) {
                    GeneratingBibliographyLoadingIndicator()
                }
                if (viewState.isGeneratingCitation) {
                    GeneratingCitationLoadingIndicator()
                }
            }
        }
        val bottomSheetTitle = stringResource(id = Strings.item_type)
        AllItemsAddBottomSheet(
            onScanBarcode = viewModel::onScanBarcode,
            onAddFile = onPickFile,
            onAddNote = viewModel::onAddNote,
            onAddManually = { viewModel.onAddManually(bottomSheetTitle) },
            onAddByIdentifier = viewModel::onAddByIdentifier,
            onAddFromUrl = viewModel::onAddFromUrl,
            onClose = viewModel::onAddBottomSheetCollapse,
            showBottomSheet = viewState.shouldShowAddBottomSheet
        )

        if (showUrlDialog) {
            AlertDialog(
                onDismissRequest = { showUrlDialog = false },
                title = { Text(stringResource(id = Strings.items_add_from_url)) },
                text = {
                    OutlinedTextField(
                        value = urlText,
                        onValueChange = { urlText = it },
                        label = { Text("URL") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            showUrlDialog = false
                            viewModel.onAddFromUrlSubmit(urlText)
                        }
                    ) {
                        Text(stringResource(id = Strings.add))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showUrlDialog = false }) {
                        Text(stringResource(id = Strings.cancel))
                    }
                },
            )
        }
    }
}