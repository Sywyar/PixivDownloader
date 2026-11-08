{ 原生报告列表负责滚动、列宽、焦点和辅助功能语义。 }
type
  TPreviewNotifyHeader = record Window, Id: Longword; Code: Integer; end;
  TPreviewListItem = record
    Mask: Longword; Item, SubItem: Integer; State, StateMask: Longword;
    Text: String; TextMax, Image: Integer; Param: Longword;
    Indent, GroupId: Integer; Columns, ColumnPointer, FormatPointer: Longword; Group: Integer;
  end;
  TPreviewColumn = record
    Mask: Longword; Format, Width: Integer; Text: String;
    TextMax, SubItem, Image, Order, MinWidth, DefaultWidth, IdealWidth: Integer;
  end;
  TPreviewNotification = record
    Window, Id: Longword; Code: Integer;
    Item, SubItem: Integer; NewState, OldState, Changed: Longword;
    X, Y: Integer; Param: Longword;
  end;
  TPreviewHit = record
    X, Y: Integer; Flags: Longword; Item, SubItem, Group: Integer;
  end;

function PreviewCreateWindow(ExStyle: Longword; ClassName, Caption: String;
  Style: Longword; X, Y, Width, Height: Integer; Parent, Menu, Instance, Param: Longword): Longword;
external 'CreateWindowExW@user32.dll stdcall';
function PreviewItemMessage(Window: Longword; Msg: Longword; Param: Longword; var Item: TPreviewListItem): Longint;
external 'SendMessageW@user32.dll stdcall';
function PreviewColumnMessage(Window: Longword; Msg: Longword; Param: Longword; var Column: TPreviewColumn): Longint;
external 'SendMessageW@user32.dll stdcall';
function PreviewHitMessage(Window: Longword; Msg: Longword; Param: Longword; var Hit: TPreviewHit): Longint;
external 'SendMessageW@user32.dll stdcall';
procedure PreviewReadNotification(var Dest: TPreviewNotification; Source: Longword; Size: Integer);
external 'RtlMoveMemory@kernel32.dll stdcall';
procedure PreviewReadHeader(var Dest: TPreviewNotifyHeader; Source: Longword; Size: Integer);
external 'RtlMoveMemory@kernel32.dll stdcall';
function PreviewSubclass(Window, Callback, Id, Data: Longword): Boolean;
external 'SetWindowSubclass@comctl32.dll stdcall';
function PreviewRemoveSubclass(Window, Callback, Id: Longword): Boolean;
external 'RemoveWindowSubclass@comctl32.dll stdcall';
function PreviewDefaultProc(Window, Msg, WParam, LParam: Longword): Longint;
external 'DefSubclassProc@comctl32.dll stdcall';
function PreviewCreateImages(Width, Height: Integer; Flags: Longword; Initial, Grow: Integer): Longword;
external 'ImageList_Create@comctl32.dll stdcall';
function PreviewAddImage(Images, Bitmap, Mask: Longword): Integer;
external 'ImageList_AddMasked@comctl32.dll stdcall';
function PreviewSystemColor(Index: Integer): Longword;
external 'GetSysColor@user32.dll stdcall';
function PreviewNativeFocus(Window: Longword): Longword;
external 'SetFocus@user32.dll stdcall';

var
  PreviewTable, PreviewImages, PreviewCallback: Longword;
  PreviewTableHost: TPanel;
  PreviewTableUpdating: Boolean;
  PreviewVisibleRows: array of Integer;

function PreviewRowSelectable(Index: Integer): Boolean; forward;
procedure PreviewToggleRow(Index: Integer; Checked: Boolean); forward;

procedure EnterPreviewTable(Sender: TObject);
var Item: TPreviewListItem;
begin
  PreviewNativeFocus(PreviewTable);
  if (SendMessage(PreviewTable, $100C, -1, 1) = -1) and (GetArrayLength(PreviewVisibleRows) > 0) then
  begin
    Item.StateMask := 3; Item.State := 3;
    PreviewItemMessage(PreviewTable, $102B, 0, Item);
  end;
end;

function PreviewTableNotify(Window, Msg, WParam, LParam, Id, Data: Longword): Longint;
var
  Notice: TPreviewNotification;
  Header: TPreviewNotifyHeader;
  Hit: TPreviewHit;
  Index, State: Integer;
begin
  Result := 0;
  if (Msg = $0113) and (WParam = 1) then
  begin
    StartPendingPluginPreview;
    exit;
  end;
  if (Msg = $004E) and (LParam <> 0) and (not PreviewTableUpdating) then
  begin
    PreviewReadHeader(Header, LParam, SizeOf(Header));
    if (Header.Window = PreviewTable) and ((Header.Code = -100) or (Header.Code = -101) or (Header.Code = -2)) then
    begin
    PreviewReadNotification(Notice, LParam, SizeOf(Notice));
    if (Notice.Item >= 0) and
       (Notice.Item < GetArrayLength(PreviewVisibleRows)) then
    begin
      Index := PreviewVisibleRows[Notice.Item];
      if (Notice.Code = -100) and (((Notice.NewState xor Notice.OldState) and $F000) <> 0) then
      begin
        if not PreviewRowSelectable(Index) then begin Result := 1; exit; end;
      end;
      if (Notice.Code = -101) and (((Notice.NewState xor Notice.OldState) and $F000) <> 0) then
        PreviewToggleRow(Index, (Notice.NewState and $F000) = $2000);
      if (Notice.Code = -2) and PreviewRowSelectable(Index) then
      begin
        Hit.X := Notice.X; Hit.Y := Notice.Y;
        PreviewHitMessage(PreviewTable, $1012, 0, Hit);
        if (Hit.Flags and $0004) <> 0 then
        begin
          State := SendMessage(PreviewTable, $102C, Notice.Item, $F000);
          PreviewToggleRow(Index, State <> $2000);
        end;
      end;
    end;
    end;
  end;
  Result := PreviewDefaultProc(Window, Msg, WParam, LParam);
end;

procedure PreviewTableCell(Row, Column: Integer; const Text: String; Image, CheckState: Integer);
var Item: TPreviewListItem;
begin
  Item.Mask := $0001;
  Item.Item := Row; Item.SubItem := Column; Item.Text := Text;
  if Column = 0 then
  begin
    Item.Mask := Item.Mask or $0002 or $0008;
    Item.Image := Image; Item.StateMask := $F000; Item.State := CheckState;
  end;
  PreviewItemMessage(PreviewTable, $104C, 0, Item);
end;

procedure PreviewTableAddRow(Index: Integer);
var Item: TPreviewListItem; Count: Integer;
begin
  Count := GetArrayLength(PreviewVisibleRows);
  SetArrayLength(PreviewVisibleRows, Count + 1); PreviewVisibleRows[Count] := Index;
  Item.Mask := $0001; Item.Item := Count; Item.Text := '';
  PreviewItemMessage(PreviewTable, $104D, 0, Item);
end;

procedure CreatePreviewTable(Page: TWizardPage; Top, Height: Integer);
var
  Column: TPreviewColumn;
  I, W, S, C, D, R, ReasonWidth, ActionWidth, NameWidth, CurrentWidth, BundledWidth: Integer;
  Bitmap: TBitmap;
  Bounds: TRect;
  Color: Longword;
  Titles: TArrayOfString;
begin
  PreviewTableHost := TPanel.Create(Page);
  PreviewTableHost.Parent := Page.Surface;
  PreviewTableHost.SetBounds(0, Top, Page.SurfaceWidth, Height);
  PreviewTableHost.BevelOuter := bvNone;
  PreviewTableHost.TabStop := True;
  PreviewTableHost.OnEnter := @EnterPreviewTable;
  PreviewTable := PreviewCreateWindow($00000200, 'SysListView32', '',
    $5001000D, 0, 0, Page.SurfaceWidth, Height, PreviewTableHost.Handle, 0, 0, 0);
  if PreviewTable = 0 then RaiseException('Could not create plugin preview table.');
  SendMessage(PreviewTable, $0030, SendMessage(WizardForm.NextButton.Handle, $0031, 0, 0), 1);
  SendMessage(PreviewTable, $1036, 0, $00010424);
  Titles := [CustomMessage('PreviewPlugin'), CustomMessage('PreviewCurrent'),
    CustomMessage('PreviewBundled'), CustomMessage('PreviewReason'), CustomMessage('PreviewAction')];
  W := Page.SurfaceWidth - ScaleX(22);
  CurrentWidth := WizardForm.CalculateButtonWidth([Titles[1]]);
  if CurrentWidth < W * 10 div 100 then CurrentWidth := W * 10 div 100;
  BundledWidth := WizardForm.CalculateButtonWidth([Titles[2]]);
  if BundledWidth < W * 10 div 100 then BundledWidth := W * 10 div 100;
  ReasonWidth := WizardForm.CalculateButtonWidth([
    CustomMessage('PreviewReason'), CustomMessage('PreviewRequired'),
    CustomMessage('PreviewCommunity'), CustomMessage('PreviewLocal'), CustomMessage('PreviewUnknownSource'),
    CustomMessage('PreviewReasonnewer'), CustomMessage('PreviewReasondifferent'),
    CustomMessage('PreviewReasonincompatible'), CustomMessage('PreviewReasonbundleIncompatible'),
    CustomMessage('PreviewReasonmissing')]);
  if ReasonWidth < W * 22 div 100 then ReasonWidth := W * 22 div 100;
  ActionWidth := WizardForm.CalculateButtonWidth([
    CustomMessage('PreviewAction'), CustomMessage('Previewinstall'), CustomMessage('Previewupgrade'),
    CustomMessage('Previewdowngrade'), CustomMessage('Previewreplace'), CustomMessage('PreviewKeep'),
    CustomMessage('PreviewResolve'), CustomMessage('PreviewUnavailable')]);
  if ActionWidth < W * 22 div 100 then ActionWidth := W * 22 div 100;
  NameWidth := W - CurrentWidth - BundledWidth - ReasonWidth - ActionWidth;
  if NameWidth < ScaleX(160) then NameWidth := ScaleX(160);
  for I := 0 to 4 do
  begin
    Column.Mask := $0006; Column.Text := Titles[I];
    if I = 0 then Column.Width := NameWidth
    else if I = 1 then Column.Width := CurrentWidth
    else if I = 2 then Column.Width := BundledWidth
    else if I = 3 then Column.Width := ReasonWidth
    else Column.Width := ActionWidth;
    PreviewColumnMessage(PreviewTable, $1061, I, Column);
  end;
  S := ScaleY(28); C := S div 2; D := ScaleY(4); R := ScaleY(6);
  PreviewImages := PreviewCreateImages(S, S, $0021, 5, 0);
  Bitmap := TBitmap.Create;
  try
    Bitmap.Width := S; Bitmap.Height := S;
    Bounds.Left := 0; Bounds.Top := 0; Bounds.Right := S; Bounds.Bottom := S;
    for I := 0 to 4 do
    begin
      Bitmap.Canvas.Brush.Color := clFuchsia;
      Bitmap.Canvas.FillRect(Bounds);
      Color := PreviewSystemColor(8);
      if PreviewSystemColor(5) = $FFFFFF then
      begin
        if I = 0 then Color := $3030B0
        else if I = 1 then Color := $006A9A
        else if I > 2 then Color := $C06000;
      end;
      Bitmap.Canvas.Pen.Color := Color; Bitmap.Canvas.Pen.Width := ScaleX(2);
      if I = 0 then begin
        Bitmap.Canvas.MoveTo(C-D, C-D); Bitmap.Canvas.LineTo(C+D, C+D);
        Bitmap.Canvas.MoveTo(C+D, C-D); Bitmap.Canvas.LineTo(C-D, C+D);
      end else if I = 1 then begin
        Bitmap.Canvas.MoveTo(C, C-R); Bitmap.Canvas.LineTo(C+R, C+D);
        Bitmap.Canvas.LineTo(C-R, C+D); Bitmap.Canvas.LineTo(C, C-R);
      end else if I = 2 then begin
        Bitmap.Canvas.MoveTo(C-D, C-D div 2); Bitmap.Canvas.LineTo(C+D, C-D div 2);
        Bitmap.Canvas.MoveTo(C-D, C+D div 2); Bitmap.Canvas.LineTo(C+D, C+D div 2);
      end else begin
        Bitmap.Canvas.MoveTo(C, C-D); Bitmap.Canvas.LineTo(C, C+D);
        if I = 3 then begin
          Bitmap.Canvas.MoveTo(C-D, C); Bitmap.Canvas.LineTo(C, C-D); Bitmap.Canvas.LineTo(C+D, C);
        end else begin
          Bitmap.Canvas.MoveTo(C-D, C); Bitmap.Canvas.LineTo(C, C+D); Bitmap.Canvas.LineTo(C+D, C);
        end;
      end;
      PreviewAddImage(PreviewImages, Bitmap.Handle, clFuchsia);
    end;
  finally Bitmap.Free; end;
  SendMessage(PreviewTable, $1003, 1, PreviewImages);
  PreviewCallback := CreateCallback(@PreviewTableNotify);
  if not PreviewSubclass(PreviewTableHost.Handle, PreviewCallback, 1, 0) then
    RaiseException('Could not initialize plugin preview input.');
end;
