// Arranque de ProxyUCI.exe: un ejecutable que lleva dentro, comprimidos, la Java recortada y la app.
//
// La primera vez descomprime todo en una carpeta con el número de versión; las siguientes arranca directo.
// Después ejecuta `javaw.exe -jar app.jar` y espera a que termine, devolviendo su código de salida (el motor
// elevado lo necesita: PowerShell espera a este proceso).
//
// Se compila con el csc.exe que trae Windows (.NET Framework 4.x), de modo que el usuario no instala nada.
// Ese compilador es C# 5: nada de interpolación de cadenas ni de `?.`.
//
// Seguridad: el motor se ejecuta como Administrador, y no debe ejecutar archivos que un usuario sin privilegios
// pueda sustituir. Por eso un proceso elevado extrae y ejecuta desde %ProgramFiles%\ProxyUCI (solo escribible
// por administradores) y uno normal lo hace desde %LOCALAPPDATA%\ProxyUCI. La ubicación no se puede cambiar con
// variables de entorno ni con argumentos.

using System;
using System.Diagnostics;
using System.IO;
using System.IO.Compression;
using System.Reflection;
using System.Runtime.InteropServices;
using System.Security.Principal;
using System.Text;

namespace ProxyUci
{
    internal static class Launcher
    {
        private const string PayloadResource = "payload.zip";
        private const string IdResource = "payload.id";
        private const string CompleteMarker = ".complete";
        private const string EngineFlag = "--engine";

        [DllImport("user32.dll", CharSet = CharSet.Unicode)]
        private static extern int MessageBoxW(IntPtr window, string text, string caption, uint type);

        private static int Main(string[] args)
        {
            bool engine = Array.IndexOf(args, EngineFlag) >= 0;
            try
            {
                return Run(args);
            }
            catch (Exception error)
            {
                string log = Path.Combine(Path.GetTempPath(), "ProxyUCI-launcher.log");
                try
                {
                    File.WriteAllText(log, error.ToString());
                }
                catch (Exception)
                {
                    // Sin dónde escribir: el mensaje de abajo es lo único que queda.
                }
                // El motor corre oculto: un cuadro de diálogo ahí no lo vería nadie y lo dejaría colgado.
                if (!engine)
                {
                    MessageBoxW(IntPtr.Zero, "ProxyUCI no pudo arrancar:\n" + error.Message + "\n\nDetalle: " + log, "ProxyUCI", 0x10);
                }
                return 1;
            }
        }

        private static int Run(string[] args)
        {
            Assembly self = Assembly.GetExecutingAssembly();
            string exe = self.Location;
            string id = ReadResourceText(self, IdResource).Trim().Substring(0, 16);

            Environment.SpecialFolder baseFolder = IsElevated()
                ? Environment.SpecialFolder.ProgramFiles
                : Environment.SpecialFolder.LocalApplicationData;
            string root = Path.Combine(Environment.GetFolderPath(baseFolder), "ProxyUCI", "app");
            string dir = Path.Combine(root, id);

            if (!File.Exists(Path.Combine(dir, CompleteMarker)))
            {
                Extract(self, root, dir);
            }

            StringBuilder command = new StringBuilder();
            command.Append(Quote("-Dproxyuci.launcher=" + exe));
            command.Append(" --enable-native-access=ALL-UNNAMED -Dfile.encoding=UTF-8 -jar ");
            command.Append(Quote(Path.Combine(dir, "app.jar")));
            foreach (string argument in args)
            {
                command.Append(' ').Append(Quote(argument));
            }

            ProcessStartInfo start = new ProcessStartInfo();
            start.FileName = Path.Combine(dir, "runtime", "bin", "javaw.exe");
            start.Arguments = command.ToString();
            start.WorkingDirectory = dir;
            start.UseShellExecute = false;
            start.CreateNoWindow = true;
            using (Process java = Process.Start(start))
            {
                java.WaitForExit();
                return java.ExitCode;
            }
        }

        /// <summary>
        /// Descomprime el contenido en una carpeta temporal y la renombra de golpe: si el proceso muere a mitad,
        /// nunca queda una carpeta a medias que parezca completa.
        /// </summary>
        private static void Extract(Assembly self, string root, string dir)
        {
            Directory.CreateDirectory(root);
            string temp = dir + ".tmp-" + Guid.NewGuid().ToString("N");
            Directory.CreateDirectory(temp);
            try
            {
                string tempPrefix = Path.GetFullPath(temp) + Path.DirectorySeparatorChar;
                using (Stream payload = self.GetManifestResourceStream(PayloadResource))
                using (ZipArchive zip = new ZipArchive(payload, ZipArchiveMode.Read))
                {
                    foreach (ZipArchiveEntry entry in zip.Entries)
                    {
                        string name = entry.FullName.Replace('/', Path.DirectorySeparatorChar);
                        string target = Path.GetFullPath(Path.Combine(temp, name));
                        // Una entrada con «..» no puede escribir fuera de la carpeta.
                        if (!target.StartsWith(tempPrefix, StringComparison.OrdinalIgnoreCase))
                        {
                            throw new InvalidDataException("Entrada fuera del destino: " + entry.FullName);
                        }
                        if (name.EndsWith(Path.DirectorySeparatorChar.ToString()))
                        {
                            Directory.CreateDirectory(target);
                            continue;
                        }
                        Directory.CreateDirectory(Path.GetDirectoryName(target));
                        entry.ExtractToFile(target, true);
                    }
                }
                File.WriteAllText(Path.Combine(temp, CompleteMarker), "ok");
                try
                {
                    Directory.Move(temp, dir);
                }
                catch (IOException)
                {
                    // Otro arranque simultáneo terminó antes; vale su carpeta si está completa.
                    if (!File.Exists(Path.Combine(dir, CompleteMarker)))
                    {
                        throw;
                    }
                }
            }
            finally
            {
                if (Directory.Exists(temp))
                {
                    try
                    {
                        Directory.Delete(temp, true);
                    }
                    catch (IOException)
                    {
                        // Se limpiará a mano; no impide arrancar.
                    }
                }
            }
        }

        private static bool IsElevated()
        {
            using (WindowsIdentity identity = WindowsIdentity.GetCurrent())
            {
                return new WindowsPrincipal(identity).IsInRole(WindowsBuiltInRole.Administrator);
            }
        }

        private static string ReadResourceText(Assembly assembly, string name)
        {
            using (Stream stream = assembly.GetManifestResourceStream(name))
            using (StreamReader reader = new StreamReader(stream, Encoding.UTF8))
            {
                return reader.ReadToEnd();
            }
        }

        /// <summary>Entrecomilla un argumento con las reglas de la línea de órdenes de Windows.</summary>
        private static string Quote(string argument)
        {
            if (argument.Length > 0 && argument.IndexOfAny(new char[] { ' ', '\t', '"' }) < 0)
            {
                return argument;
            }
            StringBuilder quoted = new StringBuilder("\"");
            int backslashes = 0;
            foreach (char c in argument)
            {
                if (c == '\\')
                {
                    backslashes++;
                    continue;
                }
                if (c == '"')
                {
                    // Las barras antes de una comilla se duplican, y la comilla se escapa.
                    quoted.Append('\\', backslashes * 2 + 1).Append('"');
                }
                else
                {
                    quoted.Append('\\', backslashes).Append(c);
                }
                backslashes = 0;
            }
            // Las barras del final se duplican para no escapar la comilla de cierre.
            quoted.Append('\\', backslashes * 2).Append('"');
            return quoted.ToString();
        }
    }
}
