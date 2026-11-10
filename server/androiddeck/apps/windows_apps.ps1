# Enumerates the Start menu "All apps" folder (desktop + Store apps) and saves each icon as PNG.
# Output: <OutDir>\apps.json = { apps: [{ name, path, icon }], shellIcons, fileIcons, errors },
#         icons as <OutDir>\<n>.png
param(
    [Parameter(Mandatory = $true)][string]$OutDir,
    [int]$Size = 128
)

$ErrorActionPreference = 'Stop'
New-Item -ItemType Directory -Force -Path $OutDir | Out-Null

Add-Type -ReferencedAssemblies System.Drawing -TypeDefinition @'
using System;
using System.Drawing;
using System.Drawing.Imaging;
using System.IO;
using System.Runtime.InteropServices;

public static class DeckIcons
{
    [ComImport]
    [Guid("bcc18b79-ba16-442f-80c4-8a59c30c463b")]
    [InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    private interface IShellItemImageFactory
    {
        [PreserveSig]
        int GetImage(SIZE size, int flags, out IntPtr phbm);
    }

    [StructLayout(LayoutKind.Sequential)]
    private struct SIZE { public int cx; public int cy; }

    [DllImport("shell32.dll", CharSet = CharSet.Unicode, PreserveSig = true)]
    private static extern int SHCreateItemFromParsingName(
        [MarshalAs(UnmanagedType.LPWStr)] string pszPath,
        IntPtr pbc,
        [MarshalAs(UnmanagedType.LPStruct)] Guid riid,
        [MarshalAs(UnmanagedType.Interface)] out IShellItemImageFactory ppv);

    [DllImport("shell32.dll", PreserveSig = true)]
    private static extern int SHGetKnownFolderPath(
        [MarshalAs(UnmanagedType.LPStruct)] Guid rfid, uint dwFlags, IntPtr hToken, out IntPtr ppszPath);

    [DllImport("gdi32.dll")]
    private static extern bool DeleteObject(IntPtr hObject);

    // Shell image of an AppsFolder item (works for desktop and Store apps).
    // Returns null on success, otherwise a short reason for the log.
    public static string SaveShellIcon(string parsingName, string outPath, int size)
    {
        IShellItemImageFactory factory = null;
        IntPtr hbm = IntPtr.Zero;
        try
        {
            int hr = SHCreateItemFromParsingName(parsingName, IntPtr.Zero, typeof(IShellItemImageFactory).GUID, out factory);
            if (hr != 0 || factory == null) return "SHCreateItemFromParsingName 0x" + hr.ToString("X8");
            SIZE sz; sz.cx = size; sz.cy = size;
            // SIIGBF_BIGGERSIZEOK (0x1) | SIIGBF_ICONONLY (0x4); then allow thumbnails as a second try.
            hr = factory.GetImage(sz, 0x1 | 0x4, out hbm);
            if (hr != 0 || hbm == IntPtr.Zero) hr = factory.GetImage(sz, 0x1, out hbm);
            if (hr != 0 || hbm == IntPtr.Zero) return "GetImage 0x" + hr.ToString("X8");
            using (Bitmap bmp = ToArgb(hbm))
            {
                bmp.Save(outPath, ImageFormat.Png);
            }
            return null;
        }
        catch (Exception e)
        {
            return e.GetType().Name + ": " + e.Message;
        }
        finally
        {
            if (hbm != IntPtr.Zero) DeleteObject(hbm);
            if (factory != null) Marshal.ReleaseComObject(factory);
        }
    }

    // AppsFolder ids of desktop apps look like "{KNOWNFOLDERID}\Vendor\app.exe" or "C:\...\app.exe".
    // Returns an existing file path, or null (e.g. for Store app ids).
    public static string ResolvePath(string appId)
    {
        if (string.IsNullOrEmpty(appId)) return null;
        string p = appId;
        if (p.StartsWith("{"))
        {
            int end = p.IndexOf('}');
            if (end < 0) return null;
            Guid id;
            try { id = new Guid(p.Substring(1, end - 1)); }
            catch { return null; }
            IntPtr raw;
            if (SHGetKnownFolderPath(id, 0, IntPtr.Zero, out raw) != 0) return null;
            string root = Marshal.PtrToStringUni(raw);
            Marshal.FreeCoTaskMem(raw);
            p = root + p.Substring(end + 1);
        }
        try { return File.Exists(p) ? p : null; }
        catch { return null; }
    }

    // Fallback when the shell image API fails: the file's associated icon (32 px).
    public static string SaveAssociatedIcon(string filePath, string outPath)
    {
        try
        {
            using (Icon icon = Icon.ExtractAssociatedIcon(filePath))
            {
                if (icon == null) return "no associated icon";
                using (Bitmap bmp = icon.ToBitmap())
                {
                    bmp.Save(outPath, ImageFormat.Png);
                }
            }
            return null;
        }
        catch (Exception e)
        {
            return e.GetType().Name + ": " + e.Message;
        }
    }

    // Image.FromHbitmap drops the alpha channel; re-wrap the 32bpp pixels as ARGB.
    private static Bitmap ToArgb(IntPtr hbm)
    {
        using (Bitmap src = Image.FromHbitmap(hbm))
        {
            if (Image.GetPixelFormatSize(src.PixelFormat) < 32) return new Bitmap(src);
            Rectangle rect = new Rectangle(0, 0, src.Width, src.Height);
            BitmapData data = src.LockBits(rect, ImageLockMode.ReadOnly, src.PixelFormat);
            try
            {
                byte[] bytes = new byte[Math.Abs(data.Stride) * data.Height];
                Marshal.Copy(data.Scan0, bytes, 0, bytes.Length);
                bool hasAlpha = false;
                for (int i = 3; i < bytes.Length; i += 4)
                {
                    if (bytes[i] != 0) { hasAlpha = true; break; }
                }
                if (!hasAlpha) return new Bitmap(src);
                using (Bitmap view = new Bitmap(data.Width, data.Height, data.Stride, PixelFormat.Format32bppArgb, data.Scan0))
                {
                    Bitmap copy = new Bitmap(data.Width, data.Height, PixelFormat.Format32bppArgb);
                    using (Graphics g = Graphics.FromImage(copy))
                    {
                        g.DrawImage(view, rect);
                    }
                    return copy;
                }
            }
            finally
            {
                src.UnlockBits(data);
            }
        }
    }
}
'@

$shell = New-Object -ComObject Shell.Application
$folder = $shell.NameSpace('shell:::{4234d49b-0245-4df3-b780-3893943456e1}')
if ($null -eq $folder) { throw 'The Applications folder (shell:AppsFolder) is not available in this session' }

$list = New-Object System.Collections.Generic.List[object]
$errors = New-Object System.Collections.Generic.List[string]
$shellOk = 0
$fileOk = 0
$i = 0
foreach ($item in $folder.Items()) {
    $name = $item.Name
    $path = $item.Path
    if ([string]::IsNullOrWhiteSpace($name) -or [string]::IsNullOrWhiteSpace($path)) { continue }
    $file = ('{0}.png' -f $i)
    $out = Join-Path $OutDir $file
    $err = [DeckIcons]::SaveShellIcon(('shell:AppsFolder\' + $path), $out, $Size)
    if ($null -eq $err) {
        $shellOk++
    } else {
        $target = [DeckIcons]::ResolvePath($path)
        if ($null -ne $target) {
            $err2 = [DeckIcons]::SaveAssociatedIcon($target, $out)
            if ($null -eq $err2) { $fileOk++; $err = $null } else { $err = $err + '; file icon: ' + $err2 }
        }
        if (($null -ne $err) -and ($errors.Count -lt 5) -and (-not $errors.Contains($err))) { $errors.Add($err) }
    }
    $icon = $null
    if ($null -eq $err) { $icon = $file }
    $list.Add([pscustomobject]@{ name = $name; path = $path; icon = $icon })
    $i++
}

$result = [pscustomobject]@{
    apps = $list.ToArray()
    shellIcons = $shellOk
    fileIcons = $fileOk
    errors = $errors.ToArray()
}
$json = ConvertTo-Json -InputObject $result -Compress -Depth 4
[System.IO.File]::WriteAllText((Join-Path $OutDir 'apps.json'), $json, (New-Object System.Text.UTF8Encoding($false)))
