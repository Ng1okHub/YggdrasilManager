import { useMemo } from "preact/hooks";
import { SearchPicker } from "./SearchPicker";

/** App metadata shown in the searchable package menu. */
export interface PackagePickerApp {
  packageName: string;
  label: string;
  uid?: number;
  isSystem: boolean;
}

/** Render a searchable package menu with controlled selection, query, and open state. */
export function PackagePicker({
  apps, selected, query, open, disabled, label = "目標 App",
  placeholder = "搜尋包名或 App 名稱", onFocus, onClose, onQuery, onSelect,
}: {
  apps: PackagePickerApp[];
  selected: string;
  query: string;
  open: boolean;
  disabled: boolean;
  label?: string;
  placeholder?: string;
  onFocus: () => void;
  onClose: () => void;
  onQuery: (value: string) => void;
  onSelect: (app: PackagePickerApp) => void;
}) {
  const options = useMemo(() => apps.map((app) => ({
    value: app.packageName,
    label: app.packageName,
    searchText: `${app.packageName} ${app.label} ${app.uid ?? ""}`,
    content: <>
      <div className="package-option-primary">
        <span className="package-option-name">{app.packageName}</span>
        <span className={`package-kind ${app.isSystem ? "package-kind--system" : "package-kind--user"}`}>
          {app.isSystem ? "系統" : "使用者"}
        </span>
      </div>
      <div className="package-option-meta"><span>{app.label}</span>{app.uid !== undefined && <span>UID {app.uid}</span>}</div>
    </>,
  })), [apps]);

  return <SearchPicker
    options={options} selected={selected} query={query} open={open} disabled={disabled}
    label={label} placeholder={placeholder} emptyMessage="找不到符合的 App"
    onFocus={onFocus} onClose={onClose} onQuery={onQuery}
    onSelect={(value) => {
      const app = apps.find((item) => item.packageName === value);
      if (app) onSelect(app);
    }}
  />;
}
