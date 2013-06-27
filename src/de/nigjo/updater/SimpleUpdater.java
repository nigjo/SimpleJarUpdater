/**
 * licensed under LGPL 3.0
 */
package de.nigjo.updater;

import java.io.IOException;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLConnection;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Utilityklasse um eine beliebige Jar-Datei zu aktualisieren. Die Updateklasse
 * kann in der zu ersetzenden Jar-Datei selbst enthalten sein.
 *
 * Beim Update ueber die Methode {@link #update(URL, Path, String[])} kopiert
 * sich die Klasse zunaechst selbst in eine temporaere Jar-Datei und startet
 * einen neuen Java-Prozess. Dieser Prozess ist für den Download der
 * "entfernten" Datei zustaendig und kopiert anschliessend die Datei zum
 * angegebenen lokalen Speicherort. Als drittes wird dann ein erneuter Prozess
 * gestartet mit der aktualisierten Jar-Datei.
 *
 * <p>Damit die Aktualisierung funktioniert darf kein aktiver Prozess auf die
 * Update-aufrufende Jar-Datei zugreifen. Sprich nach dem Aufruf der
 * {@code update()} Methode muss ein {@link System#exit(int)} oder aehnliches
 * ausgefuehrt werden. Beim Update wird ein paar Sekunden lang versucht die
 * bisherige Jar-Datei (falls vorhanden) umzubenennen, bevor der Download
 * beginnt.
 *
 * @author Jens Hofschroeer
 */
public class SimpleUpdater
{
  //<editor-fold defaultstate="collapsed" desc="Phase 1: prepare updater">
  private static final String DEFAULT_UPDATER_PREFIX =
      SimpleUpdater.class.getSimpleName();
  public static final String PROP_UPDATER_PREFIX =
      SimpleUpdater.class.getName() + ".prefix";

  /**
   * Startet den Updatevorgang. Es wird zunaechst
   *
   * @param remoteLocation entfernter Speicherort der Jar-Datei, die die
   * bisherige lokale Datei ersetzen soll. Sollte das http Protokoll fuer diese
   * URL verwendet werden, sollte die URL oeffentlich ohne Login zugaenglich
   * sein.
   * @param localDestination Lokaler Speicherort an den die Jar kopiert werden
   * soll.
   * @param restartArguments Liste der Argumente mit dem die aktualisierte Jar
   * gestartet werden soll.
   *
   * @see SimpleUpdater Detailbeschreibung im Klassenkommentar
   */
  public static void update(URL remoteLocation, Path localDestination,
      String... restartArguments)
      throws IOException
  {
    Path tempJarFile;

    String prefix = System.getProperty(PROP_UPDATER_PREFIX);
    if(prefix == null || prefix.isEmpty())
      prefix = DEFAULT_UPDATER_PREFIX;
    tempJarFile = Files.createTempFile(prefix, ".jar");

    Manifest mf = new Manifest();
    Attributes mainAttributes = mf.getMainAttributes();
    mainAttributes.put(Attributes.Name.MANIFEST_VERSION, "1.0");
    mainAttributes.put(Attributes.Name.MAIN_CLASS,
        SimpleUpdater.class.getName());

    Attributes updaterSection = new Attributes();
    mf.getEntries().put(
        SimpleUpdater.class.getPackage().getName().replace('.', '/') + '/',
        updaterSection);
    updaterSection.putValue("Remote", remoteLocation.toString());
    updaterSection.putValue("Local-Name",
        localDestination.getFileName().toString());

    try(InputStream self = SimpleUpdater.class.getResourceAsStream(
        SimpleUpdater.class.getSimpleName() + ".class");
        JarOutputStream tempUpdater = new JarOutputStream(
        Files.newOutputStream(tempJarFile), mf))
    {
      //Create updater
      JarEntry jarEntry = new JarEntry(
          SimpleUpdater.class.getName().replace('.', '/') + ".class");
      tempUpdater.putNextEntry(jarEntry);
      byte[] buffer = new byte[2048];
      int count;
      while(0 <= (count = self.read(buffer)))
        tempUpdater.write(buffer, 0, count);
    }

    //Start updater
    List<String> restartCommand = new ArrayList<>();
    Path executable = getJavaExecutable();
    restartCommand.add(executable.toAbsolutePath().toString());

    restartCommand.add("-jar");
    restartCommand.add(tempJarFile.toString());

    restartCommand.add("--remote");
    restartCommand.add(remoteLocation.toString());

    restartCommand.add("--local");
    restartCommand.add(localDestination.toAbsolutePath().toString());

    if(restartArguments.length > 0)
    {
      restartCommand.add("--args");
      restartCommand.addAll(Arrays.asList(restartArguments));
    }

    ProcessBuilder updater = new ProcessBuilder(restartCommand).inheritIO();
    updater.start();
  }
  //</editor-fold>

  //<editor-fold defaultstate="collapsed" desc="Phase 2: download and restart">
  private URL remoteLocation;
  private Path localDestination;
  private String[] restartArguments;

  /**
   * Startet das Update. Diese Methode sollte niemals direkt aufgerufen werden.
   * Um ein Selbst-Update auszuloesen eignet sich die Methode
   * {@link #update(URL, Path, String[])} besser.
   *
   * @param args Muss als erste Argumente "--remote" mit einer URL und "--local"
   * mit einem lokalen Pfad enthalten. Sollen Argumente nach dem Download an die
   * entsprechende Jar bei dessen Start weiter gereicht werden, muss "--args"
   * angegeben werden. Alle darauf folgenden Argumente nicht vom SimpleUpdater
   * selbst ausgewertet.
   *
   * @see #update(URL, Path, String[])
   */
  public static void main(String[] args)
  {
    try
    {
      SimpleUpdater updater = new SimpleUpdater();
      updater.parseCommandLine(args);

      updater.doUpdate();
    }
    catch(Exception ex)
    {
      System.err.println(ex.toString());
      System.exit(1);
    }
  }

  private void doUpdate() throws IOException, InterruptedException
  {
    if(Files.exists(localDestination))
    {
      String localFilename = localDestination.getFileName().toString();
      String sikFilename =
          localFilename.substring(0, localFilename.lastIndexOf('.')) + ".sik";

      Path backup = localDestination.resolveSibling(sikFilename);

      IOException lastException = null;
      int retryCount = 10;
      while(retryCount-- > 0)
      {
        try
        {
          Files.move(localDestination, backup,
              StandardCopyOption.REPLACE_EXISTING,
              StandardCopyOption.ATOMIC_MOVE);
          lastException = null;
          break;
        }
        catch(IOException e)
        {
          lastException = e;
          TimeUnit.SECONDS.sleep(1l);
        }
      }
      if(lastException != null)
        throw lastException;
    }

    Path parent = localDestination.getParent();
    if(!Files.exists(parent))
      Files.createDirectories(parent);
    try(InputStream remoteData = remoteLocation.openStream())
    {
      Files.copy(remoteData, localDestination,
          StandardCopyOption.REPLACE_EXISTING);
    }

    List<String> restartCommand = new ArrayList<>();
    Path executable = getJavaExecutable();
    restartCommand.add(executable.toAbsolutePath().toString());

    restartCommand.add("-jar");
    restartCommand.add(localDestination.toString());

    if(restartArguments.length > 0)
      restartCommand.addAll(Arrays.asList(restartArguments));

    ProcessBuilder restarter = new ProcessBuilder().
        command(restartCommand).
        directory(parent.toFile()).
        inheritIO();

    restarter.start();
  }

  private void parseCommandLine(String[] args)
      throws MalformedURLException, IndexOutOfBoundsException
  {
    int index;
    List<String> arguments = new ArrayList<>(Arrays.asList(args));

    index = arguments.indexOf("--remote");
    arguments.remove(index);
    remoteLocation = new URL(arguments.remove(index));

    index = arguments.indexOf("--local");
    arguments.remove(index);
    localDestination = Paths.get(arguments.remove(index));

    index = arguments.indexOf("--args");
    if(index >= 0)
    {
      while(index-- >= 0)
        arguments.remove(0);
      // Rest der Kommandozeile ist fuer den Neustart.
      restartArguments = arguments.toArray(new String[arguments.size()]);
    }
    else
    {
      restartArguments = new String[0];
    }
  }
  //</editor-fold>

  //<editor-fold defaultstate="collapsed" desc="public utilities">
  /**
   * Ermittelt den Pfad der Jar-Datei in der die Klasse enthalten ist.
   *
   * @param updateContext Klasse, in dessen Kontext die Jar-Datei gesucht werden
   * soll.
   *
   * @return Den Pfad der Jar-Datei oder {@code null}, wenn die Klassendatei
   * nicht in einer Jar-Datei enthalten ist. Dies ist haeufig im Debugger der
   * Fall.
   */
  public static Path getUpdateContext(Class<?> updateContext)
  {
    URL location =
        updateContext.getProtectionDomain().getCodeSource().getLocation();
    try
    {
      Path localJarFile;
      try
      {
        localJarFile = Paths.get(location.toURI());
      }
      catch(java.lang.IllegalArgumentException e)
      {
        // moeglicherweise UNC Pfad.
        String tmp = location.toString();
        tmp = tmp.replace("file://", "file:////");
        localJarFile = Paths.get(new java.net.URI(tmp));
      }
      if(!Files.isDirectory(localJarFile))
        return localJarFile;
    }
    catch(URISyntaxException ex)
    {
      Logger.getLogger(SimpleUpdater.class.getName()).
          log(Level.SEVERE, null, ex);
    }
    return null;
  }

  /**
   * Prueft, ob die an der URL angegeben Datei neuer ist als die lokale Version.
   *
   * @param remote Entferte Datei
   * @param local Lokale Datei. Sollte die Datei (noch) nicht existieren liefert
   * die Methode direkt {@code false} zurueck. Dieser Parameter darf nicht
   * {@code null} sein.
   *
   * @return {@code true} wenn die entfernte Datei neuer ist als die lokale
   * Variante.
   *
   * @throws IOException falls die Verbindung zur entfernten Datei nicht
   * zustande kommen kann oder sonstige Zugriffsprobleme auftreten.
   */
  public static boolean isUpToDate(URL remote, Path local)
      throws IOException
  {
    if(!Files.exists(local))
      return false;
    return isUpToDate(remote.openConnection(), local);
  }

  /**
   * Prueft, ob die entfernte Resource neuer ist als die lokale Version. Diese
   * Methode nutzt eine bestehende Verbindung zu einer entfernten Resource um
   * die Aktualitaet zu pruefen.
   *
   * @param remote Entferte Datei
   * @param local Lokale Datei. Sollte die Datei (noch) nicht existieren liefert
   * die Methode direkt {@code false} zurueck. Dieser Parameter darf nicht
   * {@code null} sein.
   *
   * @return {@code true} wenn die entfernte Datei neuer ist als die lokale
   * Variante.
   *
   * @throws IOException falls die Verbindung zur entfernten Datei nicht
   * zustande kommen kann oder sonstige Zugriffsprobleme auftreten.
   *
   * @see #isUpToDate(URL, Path)
   */
  public static boolean isUpToDate(URLConnection remote, Path local)
      throws IOException
  {
    if(!Files.exists(local))
      return false;
    long lastLocalChange = Files.getLastModifiedTime(local).toMillis();
    long lastRemoteChange = remote.getLastModified();

    boolean uptodate = lastRemoteChange != 0
        && lastRemoteChange < lastLocalChange;

    return uptodate;
  }
  //</editor-fold>

  //<editor-fold defaultstate="collapsed" desc="private utilities">
  private SimpleUpdater()
  {
  }

  private static Path getJavaExecutable()
  {
    String javaHome = System.getProperty("java.home");
    Path executable = Paths.get(javaHome, "bin", "java");
    if(System.console() == null)
    {
      Path windowsExe = executable.resolveSibling("javaw.exe");
      if(Files.exists(windowsExe))
        executable = windowsExe;
    }
    return executable;
  }
  //</editor-fold>
}