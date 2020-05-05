/*
 * SourcePane.java
 *
 * Copyright (C) 2009-19 by RStudio, PBC
 *
 * Unless you have received this program directly from RStudio pursuant
 * to the terms of a commercial license agreement with RStudio, then
 * this program is licensed to you under the terms of version 3 of the
 * GNU Affero General Public License. This program is distributed WITHOUT
 * ANY EXPRESS OR IMPLIED WARRANTY, INCLUDING THOSE OF NON-INFRINGEMENT,
 * MERCHANTABILITY OR FITNESS FOR A PARTICULAR PURPOSE. Please refer to the
 * AGPL (http://www.gnu.org/licenses/agpl-3.0.txt) for more details.
 *
 */
package org.rstudio.studio.client.workbench.views.source;

import com.google.gwt.core.client.GWT;
import com.google.gwt.core.client.Scheduler;
import com.google.gwt.dom.client.Style.Cursor;
import com.google.gwt.dom.client.Style.Unit;
import com.google.gwt.event.logical.shared.BeforeSelectionHandler;
import com.google.gwt.event.logical.shared.CloseEvent;
import com.google.gwt.event.logical.shared.CloseHandler;
import com.google.gwt.event.logical.shared.SelectionHandler;
import com.google.gwt.event.logical.shared.ValueChangeEvent;
import com.google.gwt.event.logical.shared.ValueChangeHandler;
import com.google.gwt.event.shared.HandlerRegistration;
import com.google.gwt.user.client.Command;
import com.google.gwt.user.client.ui.*;
import com.google.inject.Inject;
import com.google.inject.Provider;

import org.rstudio.core.client.Debug;
import org.rstudio.core.client.ResultCallback;
import org.rstudio.core.client.StringUtil;
import org.rstudio.core.client.command.CommandBinder;
import org.rstudio.core.client.events.*;
import org.rstudio.core.client.js.JsObject;
import org.rstudio.core.client.js.JsUtil;
import org.rstudio.core.client.layout.RequiresVisibilityChanged;
import org.rstudio.core.client.resources.ImageResource2x;
import org.rstudio.core.client.theme.DocTabLayoutPanel;
import org.rstudio.core.client.theme.res.ThemeResources;
import org.rstudio.core.client.theme.res.ThemeStyles;
import org.rstudio.core.client.widget.BeforeShowCallback;
import org.rstudio.core.client.widget.OperationWithInput;

import org.rstudio.studio.client.RStudioGinjector;
import org.rstudio.studio.client.application.events.EventBus;
import org.rstudio.studio.client.server.ServerError;
import org.rstudio.studio.client.server.ServerRequestCallback;
import org.rstudio.studio.client.server.VoidServerRequestCallback;
import org.rstudio.studio.client.common.AutoGlassAttacher;
import org.rstudio.studio.client.common.SimpleRequestCallback;
import org.rstudio.studio.client.common.filetypes.EditableFileType;
import org.rstudio.studio.client.common.filetypes.FileIcon;
import org.rstudio.studio.client.common.filetypes.FileTypeRegistry;
import org.rstudio.studio.client.common.filetypes.TextFileType;
import org.rstudio.studio.client.workbench.commands.Commands;
import org.rstudio.studio.client.workbench.model.RemoteFileSystemContext;
import org.rstudio.studio.client.workbench.model.UnsavedChangesTarget;
import org.rstudio.studio.client.workbench.ui.unsaved.UnsavedChangesDialog;
import org.rstudio.studio.client.workbench.views.source.Source.Display;
import org.rstudio.studio.client.workbench.views.source.editors.EditingTarget;
import org.rstudio.studio.client.workbench.views.source.editors.EditingTargetSource;
import org.rstudio.studio.client.workbench.views.source.editors.text.TextEditingTarget;
import org.rstudio.studio.client.workbench.views.source.events.*;
import org.rstudio.studio.client.workbench.views.source.model.SourceDocument;
import org.rstudio.studio.client.workbench.views.source.model.SourceNavigation;
import org.rstudio.studio.client.workbench.views.source.model.SourceNavigationHistory;
import org.rstudio.studio.client.workbench.views.source.model.SourceServerOperations;

import java.util.ArrayList;

public class SourcePane extends LazyPanel implements Display,
                                                     BeforeShowCallback,
                                                     DocWindowChangedEvent.Handler,
                                                     EnsureVisibleSourceWindowEvent.Handler,
                                                     HasEnsureVisibleHandlers,
                                                     HasEnsureHeightHandlers,
                                                     MaximizeSourceWindowEvent.Handler,
                                                     ProvidesResize,
                                                     RequiresResize,
                                                     RequiresVisibilityChanged,
                                                     TabCloseHandler,
                                                     TabClosedHandler,
                                                     TabClosingHandler,
                                                     TabReorderHandler
{
   public interface Binder extends CommandBinder<Commands, SourcePane> {}

   @Inject
   public SourcePane()
   {   
      Commands commands = RStudioGinjector.INSTANCE.getCommands();
      Binder binder = GWT.<Binder>create(Binder.class);
      binder.bind(commands, this);
      events_ = RStudioGinjector.INSTANCE.getEventBus();
      events_.addHandler(TabClosingEvent.TYPE, this);
      events_.addHandler(TabCloseEvent.TYPE, this);
      events_.addHandler(TabClosedEvent.TYPE, this);
      events_.addHandler(TabReorderEvent.TYPE, this);
      events_.addHandler(MaximizeSourceWindowEvent.TYPE, this);
      events_.addHandler(EnsureVisibleSourceWindowEvent.TYPE, this);
      events_.addHandler(DocWindowChangedEvent.TYPE, this);

      setVisible(true);
      ensureWidget();

      if (getTabCount() > 0 && getActiveTabIndex() >= 0)
         editors_.get(getActiveTabIndex()).onInitiallyLoaded();
   }

   @Override
   protected Widget createWidget()
   {
      final int UTILITY_AREA_SIZE = 74;

      panel_ = new LayoutPanel();

      new AutoGlassAttacher(panel_);

      tabPanel_ =  new DocTabLayoutPanel(true, 65, UTILITY_AREA_SIZE);
      panel_.setSize("100%", "100%");
      panel_.add(tabPanel_);
      panel_.setWidgetTopBottom(tabPanel_, 0, Unit.PX, 0, Unit.PX);
      panel_.setWidgetLeftRight(tabPanel_, 0, Unit.PX, 0, Unit.PX);

      utilPanel_ = new HTML();
      utilPanel_.setStylePrimaryName(ThemeStyles.INSTANCE.multiPodUtilityArea());
      panel_.add(utilPanel_);
      panel_.setWidgetRightWidth(utilPanel_,
                                    0, Unit.PX,
                                    UTILITY_AREA_SIZE, Unit.PX);
      panel_.setWidgetTopHeight(utilPanel_, 0, Unit.PX, 22, Unit.PX);

      tabOverflowPopup_ = new TabOverflowPopupPanel();
      tabOverflowPopup_.addCloseHandler(new CloseHandler<PopupPanel>()
      {
         public void onClose(CloseEvent<PopupPanel> popupPanelCloseEvent)
         {
            manageChevronVisibility();
         }
      });
      chevron_ = new Image(new ImageResource2x(ThemeResources.INSTANCE.chevron2x()));
      chevron_.setAltText("Switch to tab");
      chevron_.getElement().getStyle().setCursor(Cursor.POINTER);
      chevron_.addClickHandler(event -> tabOverflowPopup_.showRelativeTo(chevron_));

      panel_.add(chevron_);
      panel_.setWidgetTopHeight(chevron_,
                                8, Unit.PX,
                                chevron_.getHeight(), Unit.PX);
      panel_.setWidgetRightWidth(chevron_,
                                 52, Unit.PX,
                                 chevron_.getWidth(), Unit.PX);
      
      return panel_;
   }

   @Override
   protected void onLoad()
   {
      super.onLoad();
      Scheduler.get().scheduleDeferred(() -> onResize());
   }

   protected void onUnload()
   {
   }

   @Override
   public String getName()
   {
      return name_;
   }

   @Override
   public void generateName(boolean first)
   {
      if (StringUtil.isNullOrEmpty(name_))
      {
         if (first)
            name_ = "Source";
         else
            name_ = Source.COLUMN_PREFIX + StringUtil.makeRandomId(12);
      }
   }

   @Override
   public boolean hasDoc(String docId)
   {
      for (EditingTarget target : editors_)
      {
         if (StringUtil.equals(docId, target.getId()))
            return true;
      }
      return false;
   }

   // public add tab methods

   @Override
   public void addEditor(EditingTarget target)
   {
      if (editors_.contains(target))
         Debug.logToConsole("Trying to add editor we already have");
      else
         editors_.add(target);
   }

   public void addTab(Widget widget,
                      FileIcon icon,
                      String docId,
                      String name,
                      String tooltip,
                      Integer position,
                      boolean switchToTab)
   {
      tabPanel_.add(widget, icon, docId, name, tooltip, position);
      if (switchToTab)
         tabPanel_.selectTab(widget);
   }

   public void setPhysicalTabIndex(int idx)
   {
      if (idx < tabOrder_.size())
      {
         idx = tabOrder_.get(idx);
      }
      selectTab(idx);
   }

   // end public add tab methods

   // update tabs methods

   @Override
   public void onTabReorder(TabReorderEvent event)
   {
      syncTabOrder();

      // sanity check: make sure we're moving from a valid location and to a
      // valid location
      if (event.getOldPos() < 0 || event.getOldPos() >= tabOrder_.size() ||
          event.getNewPos() < 0 || event.getNewPos() >= tabOrder_.size())
      {
         return;
      }

      // sort the document IDs and send to the server
      ArrayList<String> ids = new ArrayList<String>();
      for (int i = 0; i < tabOrder_.size(); i++)
      {
         ids.add(editors_.get(tabOrder_.get(i)).getId());
      }
      source_.getServer().setDocOrder(ids, new VoidServerRequestCallback());

      // activate the tab
      setPhysicalTabIndex(event.getNewPos());

      //fireDocTabsChanged();
   }

   private void syncTabOrder()
   {
      // ensure the tab order is synced to the list of editors
      for (int i = tabOrder_.size(); i < editors_.size(); i++)
      {
         tabOrder_.add(i);
      }
      for (int i = editors_.size(); i < tabOrder_.size(); i++)
      {
         tabOrder_.remove(i);
      }
   }

   // end update tabs methods

   // close tab methods

   public void onTabClosing(final TabClosingEvent event)
   {
      EditingTarget target = editors_.get(event.getTabIndex());
      if (!target.onBeforeDismiss())
         event.cancel();
   }

   @Override
   public void onTabClose(TabCloseEvent event)
   {
      // can't proceed if there is no active editor or display
      if (activeEditor_ == null)
         return;

      if (event.getTabIndex() >= editors_.size())
         return; // Seems like this should never happen...?

      final String activeEditorId = activeEditor_.getId();

      if (editors_.get(event.getTabIndex()).getId() == activeEditorId)
      {
         // scan the source navigation history for an entry that can
         // be used as the next active tab (anything that doesn't have
         // the same document id as the currently active tab)
         SourceNavigation srcNav = sourceNavigationHistory_.scanBack(
               new SourceNavigationHistory.Filter()
               {
                  public boolean includeEntry(SourceNavigation navigation)
                  {
                     return navigation.getDocumentId() != activeEditorId;
                  }
               });

         // see if the source navigation we found corresponds to an active
         // tab -- if it does then set this on the event
         if (srcNav != null)
         {
            for (int i=0; i<editors_.size(); i++)
            {
               if (srcNav.getDocumentId() == editors_.get(i).getId())
               {
                  selectTab(i);
                  break;
               }
            }
         }
      }
   }

   public void closeTabByPath(String path, boolean interactive)
   {
      // !!! temporary debugging variable
      boolean found = false;
      for (int i = 0; i < editors_.size(); i++)
      {
         if (editors_.get(i).getPath() == path)
         {
            found = true;
            closeTab(i, interactive, null);
            break;
         }
      }
      if (!found)
         Debug.logToConsole("COULD NOT FIND TAB TO CLOSE BY PATH");
   }

   public void closeTabByDocId(String docId, boolean interactive)
   {
      suspendDocumentClose_ = true;
      for (int i = 0; i < editors_.size(); i++)
      {
         if (editors_.get(i).getId() == docId)
         {
            closeTab(i, interactive, null);
            break;
         }
      }
      suspendDocumentClose_ = false;
   }

   public void closeTab(boolean interactive)
   {
      closeTab(getActiveTabIndex(), interactive, null);
   }

   public void closeTab(Widget child, boolean interactive)
   {
      closeTab(child, interactive, null);
   }

   public void closeTab(Widget child, boolean interactive, Command onClosed)
   {
      closeTab(tabPanel_.getWidgetIndex(child), interactive, onClosed);
   }
   
   public void closeTab(int index, boolean interactive, Command onClosed)
   {
      if (interactive)
      {
         if (tabPanel_.tryCloseTab(index, onClosed))
            editors_.remove(index);
      }
      else
      {
         tabPanel_.closeTab(index, onClosed);
         editors_.remove(index);
      }
   }

   public void onTabClosed(TabClosedEvent event)
   {
      closeTabIndex(event.getTabIndex(), !suspendDocumentClose_);
   }

   private void closeTabIndex(int idx, boolean closeDocument)
   {
      EditingTarget target = editors_.remove(idx);

      tabOrder_.remove(new Integer(idx));
      for (int i = 0; i < tabOrder_.size(); i++)
      {
         if (tabOrder_.get(i) > idx)
         {
            tabOrder_.set(i, tabOrder_.get(i) - 1);
         }
      }

      target.onDismiss(closeDocument ? EditingTarget.DISMISS_TYPE_CLOSE :
         EditingTarget.DISMISS_TYPE_MOVE);
      source_.closeEditorIfActive(target);

      if (closeDocument)
      {
         events_.fireEvent(new DocTabClosedEvent(target.getId()));
         source_.getServer().closeDocument(target.getId(),
                               new VoidServerRequestCallback());
      }

      //manageCommands(); !!! need to handle this
      //fireDocTabsChanged(); !!! need to handle this

      // !!! JAVASCRIPT EXCEPTION - "Cannot read property getTabCount of null"
      if (getTabCount() == 0)
      {
         sourceNavigationHistory_.clear();
         events_.fireEvent(new LastSourceDocClosedEvent());
      }
   }

   // public close tab methods
   
   @Override
   public void setActiveEditor(EditingTarget target)
   {
      activeEditor_ = target;
      if (!editors_.contains(activeEditor_))
      {
         addEditor(activeEditor_);
         Debug.logToConsole("UNKNOWN ACTIVE EDITOR, ADDED TO LIST");
      }

   }

   public void setDirty(Widget widget, boolean dirty)
   {
      Widget tab = tabPanel_.getTabWidget(widget);
      if (dirty)
         tab.addStyleName(ThemeStyles.INSTANCE.dirtyTab());
      else
         tab.removeStyleName(ThemeStyles.INSTANCE.dirtyTab());
   }

   public void ensureVisible()
   {
      events_.fireEvent(new EnsureVisibleEvent(true));
   }

   public void renameTab(Widget child,
                         FileIcon icon,
                         String value,
                         String tooltip)
   {
      tabPanel_.replaceDocName(tabPanel_.getWidgetIndex(child),
                               icon,
                               value,
                               tooltip);
   }

   public void onNewSourceDoc()
   {
      String breakpoint = "breakpoint";
      EditableFileType fileType = FileTypeRegistry.R;

      TextFileType textType = (TextFileType)fileType;
      source_.getServer().getSourceTemplate("",
            "default" + textType.getDefaultExtension(),
            new ServerRequestCallback<String>()
            {
               @Override
               public void onResponseReceived(String template)
               {
                  // Create a new document with the supplied template
                  newDoc(fileType, template, null);
               }

               @Override
               public void onError(ServerError error)
               {
                  // Ignore errors; there's just not a template for this type
                  newDoc(fileType, null, null);
               }
            });
   }

   @Override
   public void setSource(Source source)
   {
      source_ = source;
   }

   public int getActiveTabIndex()
   {
      return tabPanel_.getSelectedIndex();
   }

   public void selectTab(int tabIndex)
   {
      tabPanel_.selectTab(tabIndex);
   }

   public void selectTab(Widget child)
   {
      tabPanel_.selectTab(child);
   }

   public int getTabCount()
   {
      return tabPanel_.getWidgetCount();
   }

   public void addToPanel(Widget w)
   {
      panel_.add(w);
   }

   @Override
   public void moveTab(int index, int delta)
   {
      tabPanel_.moveTab(index, delta);
   }

   public HandlerRegistration addTabClosingHandler(TabClosingHandler handler)
   {
      return tabPanel_.addTabClosingHandler(handler);
   }

   public HandlerRegistration addTabCloseHandler(
         TabCloseHandler handler)
   {
      return tabPanel_.addTabCloseHandler(handler);
   }
   
   public HandlerRegistration addTabClosedHandler(TabClosedHandler handler)
   {
      return tabPanel_.addTabClosedHandler(handler);
   }

   @Override
   public HandlerRegistration addTabReorderHandler(TabReorderHandler handler)
   {
      return tabPanel_.addTabReorderHandler(handler);
   }
 
   public HandlerRegistration addSelectionHandler(SelectionHandler<Integer> handler)
   {
      return tabPanel_.addSelectionHandler(handler);
   }

   public HandlerRegistration addBeforeSelectionHandler(BeforeSelectionHandler<Integer> handler)
   {
      return tabPanel_.addBeforeSelectionHandler(handler);
   }

   public Widget asWidget()
   {
      ensureVisible();
      return this;
   }

   public HandlerRegistration addEnsureVisibleHandler(EnsureVisibleHandler handler)
   {
      return addHandler(handler, EnsureVisibleEvent.TYPE);
   }
   
   public HandlerRegistration addEnsureHeightHandler(
         EnsureHeightHandler handler)
   {
      return addHandler(handler, EnsureHeightEvent.TYPE);
   }

   @Override
   public void onMaximizeSourceWindow(MaximizeSourceWindowEvent e)
   {
      events_.fireEvent(new EnsureVisibleEvent());
      events_.fireEvent(new EnsureHeightEvent(EnsureHeightEvent.MAXIMIZED));
   }

   @Override
   public void onEnsureVisibleSourceWindow(EnsureVisibleSourceWindowEvent e)
   {
      if (getTabCount() > 0)
      {
         events_.fireEvent(new EnsureVisibleEvent());
         events_.fireEvent(new EnsureHeightEvent(EnsureHeightEvent.NORMAL));
      }
   }

   @Override
   public void onDocWindowChanged(final DocWindowChangedEvent e)
   {
   }

   public void onResize()
   {
      panel_.onResize();
      manageChevronVisibility();
   }

   public void manageChevronVisibility()
   {
      int tabsWidth = tabPanel_.getTabsEffectiveWidth();
      setOverflowVisible(tabsWidth > getOffsetWidth() - 50);
   }

   public void showOverflowPopup()
   {
      setOverflowVisible(true);
      tabOverflowPopup_.showRelativeTo(chevron_);
   }
   
   @Override
   public void showUnsavedChangesDialog(
         String title,
         ArrayList<UnsavedChangesTarget> dirtyTargets,
         OperationWithInput<UnsavedChangesDialog.Result> saveOperation,
         Command onCancelled)
   {
      new UnsavedChangesDialog(title, 
                               dirtyTargets,
                               saveOperation,
                               onCancelled).showModal();
   }

   private void setOverflowVisible(boolean visible)
   {
      utilPanel_.setVisible(visible);
      chevron_.setVisible(visible);
   }

   public void onBeforeShow()
   {
      for (Widget w : panel_)
         if (w instanceof BeforeShowCallback)
            ((BeforeShowCallback)w).onBeforeShow();
      events_.fireEvent(new BeforeShowEvent());
   }

   public HandlerRegistration addBeforeShowHandler(BeforeShowHandler handler)
   {
      return addHandler(handler, BeforeShowEvent.TYPE);
   }

   public void onVisibilityChanged(boolean visible)
   {
      if (getActiveTabIndex() >= 0)
      {
         Widget w = tabPanel_.getTabWidget(getActiveTabIndex());
         if (w instanceof RequiresVisibilityChanged)
            ((RequiresVisibilityChanged)w).onVisibilityChanged(visible);
      }
   }
   
   public void cancelTabDrag()
   {
      tabPanel_.cancelTabDrag();
   }

   private void newDoc(EditableFileType fileType,
                       final String contents,
                       final ResultCallback<EditingTarget, ServerError> resultCallback)
   {
      ensureVisible();
      source_.getServer().newDocument(
            fileType.getTypeId(),
            contents,
            JsObject.createJsObject(),
            new SimpleRequestCallback<SourceDocument>(
               "Error Creating New Document")
            {
               @Override
               public void onResponseReceived(SourceDocument newDoc)
               {
                  EditingTarget target = addTab(newDoc, OPEN_INTERACTIVE);
                  activeEditor_ = target;

                  if (contents != null)
                  {
                     target.forceSaveCommandActive();
                     //manageSaveCommands(); !!! how will this work?
                  }
   
                  if (resultCallback != null)
                     resultCallback.onSuccess(target);
               }

               @Override
               public void onError(ServerError error)
               {
                  if (resultCallback != null)
                     resultCallback.onFailure(error);
               }
            });
   }

   private EditingTarget addTab(SourceDocument doc, int mode)
   {
      final String defaultNamePrefix = source_.getEditingTargetSource().getDefaultNamePrefix(doc);
      final EditingTarget target = source_.getEditingTargetSource().getEditingTarget(
            doc, source_.getFileContext(), new Provider<String>()
            {
               public String get()
               {
                  return source_.getNextDefaultName(defaultNamePrefix);
               }
            });
      final Widget widget = createWidget(target);

      Integer position = getActiveTabIndex() + 1;

      if (position == null)
      {
         addEditor(target);
      }
      else
      {
         // we're inserting into an existing permuted tabset -- push aside
         // any tabs physically to the right of this tab
         editors_.add(position, target);
         source_.addEditor(target);
         for (int i = 0; i < tabOrder_.size(); i++)
         {
            int pos = tabOrder_.get(i);
            if (pos >= position)
               tabOrder_.set(i, pos + 1);
         }
   
         // add this tab in its "natural" position
         tabOrder_.add(position, position);
      }

      addTab(widget,
             target.getIcon(),
             target.getId(),
             target.getName().getValue(),
             target.getTabTooltip(), // used as tooltip, if non-null
             position,
             true);
      //fireDocTabsChanged();

      target.getName().addValueChangeHandler(new ValueChangeHandler<String>()
      {
         public void onValueChange(ValueChangeEvent<String> event)
         {
            renameTab(widget,
                      target.getIcon(),
                      event.getValue(),
                      target.getPath());
            //fireDocTabsChanged();
         }
      });

      setDirty(widget, target.dirtyState().getValue());
      target.dirtyState().addValueChangeHandler(new ValueChangeHandler<Boolean>()
      {
         public void onValueChange(ValueChangeEvent<Boolean> event)
         {
            setDirty(widget, event.getValue());
            //manageCommands();
         }
      });

      target.addEnsureVisibleHandler(new EnsureVisibleHandler()
      {
         public void onEnsureVisible(EnsureVisibleEvent event)
         {
            selectTab(widget);
         }
      });

      target.addCloseHandler(new CloseHandler<Void>()
      {
         public void onClose(CloseEvent<Void> voidCloseEvent)
         {
            closeTab(widget, false);
         }
      });

      events_.fireEvent(new SourceDocAddedEvent(doc, mode));

      if (target instanceof TextEditingTarget && doc.isReadOnly())
      {
         ((TextEditingTarget) target).setIntendedAsReadOnly(
            JsUtil.toList(doc.getReadOnlyAlternatives()));
      }

      // adding a tab may enable commands that are only available when
      // multiple documents are open; if this is the second document, go check
      //if (editors_.size() == 2)
      //   manageMultiTabCommands(); !!! fix this

      // if the target had an editing session active, attempt to resume it
      if (doc.getCollabParams() != null)
         target.beginCollabSession(doc.getCollabParams());

      return target;
   }

   private Widget createWidget(EditingTarget target)
   {
      return target.asWidget();
   }

   private boolean suspendDocumentClose_ = false;

   private String name_;
   private Source source_;
   private DocTabLayoutPanel tabPanel_;
   private HTML utilPanel_;
   private Image chevron_;
   private LayoutPanel panel_;
   private PopupPanel tabOverflowPopup_;
   private EventBus events_;

   EditingTarget activeEditor_;
   ArrayList<EditingTarget> editors_ = new ArrayList<EditingTarget>();
   ArrayList<Integer> tabOrder_ = new ArrayList<Integer>();

   private final SourceNavigationHistory sourceNavigationHistory_ =
                                              new SourceNavigationHistory(30);
   public final static int OPEN_INTERACTIVE = 0;
}
