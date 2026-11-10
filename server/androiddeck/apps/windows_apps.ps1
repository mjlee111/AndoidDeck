# Enumerates the Start menu "All apps" folder (desktop + Store apps) and saves each icon as PNG.
# Output: <OutDir>\apps.json = [{ name, path, icon }], icons as <OutDir>\<n>.png
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

    [DllImport("shell32.dll", CharSet = CharSet.Unicode, PreserveSig = false)]
    private static extern void SHCreateItemFromParsingName(
        [MarshalAs(UnmanagedType.LPWStr)] string pszPath,
        IntPtr pbc,
        [MarshalAs(UnmanagedType.LPStruct)] Guid riid,
        [MarshalAs(UnmanagedType.Interface)] out IShellItemImageFactory ppv);

    [DllImport("gdi32.dll")]
    private static extern bool DeleteObject(IntPtr hObject);

    public static bool SavePng(string parsingName, string outPath, int size)
    {
        IShellItemImageFactory factory = null;
        IntPtr hbm = IntPtr.Zero;
        try
        {
            SHCreateItemFromParsingName(parsingName, IntPtr.Zero, typeof(IShellItemImageFactory).GUID, out factory);
            SIZE sz; sz.cx = size; sz.cy = size;
            // SIIGBF_BIGGERSIZEOK (0x1) | SIIGBF_ICONONLY (0x4)
            int hr = factory.GetImage(sz, 0x1 | 0x4, out hbm);
            if (hr != 0 || hbm == IntPtr.Zero) return false;
            using (Bitmap bmp = ToArgb(hbm))
            {
                bmp.Save(outPath, ImageFormat.Png);
            }
            return true;
        }
        catch
        {
            return false;
        }
        finally
        {
            if (hbm != IntPtr.Zero) DeleteObject(hbm);
            if (factory != null) Marshal.ReleaseComObject(factory);
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
$list = New-Object System.Collections.Generic.List[object]
$i = 0
foreach ($item in $folder.Items()) {
    $name = $item.Name
    $path = $item.Path
    if ([string]::IsNullOrWhiteSpace($name) -or [string]::IsNullOrWhiteSpace($path)) { continue }
    $file = ('{0}.png' -f $i)
    $ok = [DeckIcons]::SavePng(('shell:AppsFolder\' + $path), (Join-Path $OutDir $file), $Size)
    $icon = $null
    if ($ok) { $icon = $file }
    $list.Add([pscustomobject]@{ name = $name; path = $path; icon = $icon })
    $i++
}

$json = ConvertTo-Json -InputObject $list.ToArray() -Compress -Depth 3
[System.IO.File]::WriteAllText((Join-Path $OutDir 'apps.json'), $json, (New-Object System.Text.UTF8Encoding($false)))
