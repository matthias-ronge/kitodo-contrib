// (c) MIK-Center GmbH <info@mik-center.de>
// This file is licensed under GNU General Public License version 3 or later.

package de.mik_center.custom.orff_zentrum.mediadb_csv_to_xml;

import java.io.*;
import java.nio.file.*;
import java.time.*;
import java.time.format.*;
import java.util.*;
import java.util.regex.*;

import static java.nio.charset.StandardCharsets.*;

public class MediaDbMapper {

    static final Pattern           LASTNAME_FIRSTNAME    = Pattern.compile("((?:(.*), )(.*))");
    static final DateTimeFormatter METS_DATE_TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSXXX");


    public static void main(String[] args) throws Exception {

        final Path         workDir        = Path.of("resources/");
        final Path         csvFile        = workDir.resolve("Video.csv");
        final String       docType        = "orff_recording_medium";
        final int          firstProcessId = 2337; 

        List<List<String>> table         = readExcelCsvTable(csvFile);
        List<String>       processTitles = writeMetaFiles(docType, table, firstProcessId, workDir);

        System.out.println("docType,CatalogIDDigital");
        System.out.println(docType + "," + String.join("\n" + docType + ",", processTitles));
    }

    static List<List<String>> readExcelCsvTable(Path inPath) throws IOException {
    
        List<String> lines = Files.readAllLines(inPath);
        
        // recombine linebreaks in
        final String linebreakHint = "_x000d_";
        for (int lineNumber = 0; lineNumber < lines.size(); lineNumber++) {
            String line = lines.get(lineNumber);
            if (line.endsWith(linebreakHint)) {
                int next = lineNumber + 1;
                String line2 = lines.get(next);
                lines.set(lineNumber, line.substring(0, line.length() - linebreakHint.length()) + '\n' + line2);
                lines.remove(next);
            }
        }
    
        // split cells
        List<List<String>> table = new ArrayList<>();
        for (String line : lines) {
            List<String> row = new ArrayList<>();
            StringBuilder cellContent = new StringBuilder();
            boolean quoted = false;
            boolean afterQuotes = false;
            for (int offset = 0; offset < line.length();) {
                int codepoint = line.codePointAt(offset);
                switch (codepoint) {
                    case '"':
                        if (afterQuotes) {
                            cellContent.append('"');
                            quoted = true;
                            afterQuotes = false;
                        } else {
                            quoted = !quoted;
                            afterQuotes = true;
                        }
                    break;
                    case ',':
                        afterQuotes = false;
                        if (quoted) {
                            cellContent.append(',');
                        } else {
                            row.add(cellContent.toString());
                            cellContent = new StringBuilder();
                        }
                    break;
                    default:
                        afterQuotes = false;
                        cellContent.appendCodePoint(codepoint);
                    break;
                }
                offset += Character.charCount(codepoint);
            }
            row.add(cellContent.toString());
            table.add(row);
        }
        return table;
    }

    static List<String> writeMetaFiles(String docType, List<List<String>> table, int firstProcessId, Path outputRootDir) throws IOException {
        List<String> processTitles = new ArrayList<>();
        List<String> headings = table.get(0);
        for (int line = 1; line < table.size(); line++) {
            int processId = firstProcessId + line - 1;
            Path processDir = outputRootDir.resolve(Integer.toString(processId));
            if (!Files.exists(processDir)) Files.createDirectory(processDir);
            Path metaPath = processDir.resolve("meta.xml");
            try (PrintStream out = new PrintStream(Files.newOutputStream(metaPath), false, UTF_8)) {
                out.println("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>");
                out.println("<mets:mets xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\" xmlns:xlink=\"http://www.w3.org/1999/xlink\" xmlns:kitodo=\"http://meta.kitodo.org/v1/\" xmlns:mets=\"http://www.loc.gov/METS/\">");
                out.println("    <mets:metsHdr CREATEDATE=\"" + OffsetDateTime.now().format(METS_DATE_TIME_FORMAT) + "\">");
                out.println("        <mets:metsDocumentID>" + processId + "</mets:metsDocumentID>");
                out.println("    </mets:metsHdr>");
                out.println("    <mets:dmdSec ID=\"dmdLogRoot\">");
                out.println("        <mets:mdWrap MDTYPE=\"OTHER\" OTHERMDTYPE=\"KITODO\">");
                out.println("            <mets:xmlData>");
                out.println("                <kitodo:kitodo>");
                processTitles.add ( writeProcessMetadata(headings, table.get(line), out) );
                out.println("                </kitodo:kitodo>");
                out.println("            </mets:xmlData>");
                out.println("        </mets:mdWrap>");
                out.println("    </mets:dmdSec>");
                out.println("    <mets:structMap TYPE=\"PHYSICAL\">");
                out.println("        <mets:div ID=\"physRoot\"/>");
                out.println("    </mets:structMap>");
                out.println("    <mets:structMap TYPE=\"LOGICAL\">");
                out.println("        <mets:div ID=\"logRoot\" DMDID=\"dmdLogRoot\" TYPE=\"" + docType + "\"/>");
                out.println("    </mets:structMap>");
                out.println("</mets:mets>");
            }
        }
        return processTitles;
    }

    static String writeProcessMetadata(List<String> columns, List<String> values, PrintStream out) {
        String processTitle = null;
        for (int column = 0; column < values.size(); column++) {
            String columnHeading = columns.get(column);
            String value = values.get(column);
            switch (columnHeading) {
                case "processTitle":
                    processTitle = value;
                break;
                case "Musik":
                    writePerson(value, "cmp", "Musik", out);
                break;
                case "Autor":
                    if (!value.isEmpty()) {
                        for (String autor : Arrays.asList(value.split(" ?[;/] "))) {
                            writePerson(autor, "aut", "Autor", out);
                        }
                    }
                break;
                case "Hersteller/ Regie":
                    if (!value.isEmpty()) {
                        boolean hersteller = true;
                        for (String herstellerOrRegie : Arrays.asList(value.split(" ?/ ?"))) {
                            if (hersteller) {
                                writeInstitution(herstellerOrRegie, "mfr", "Hersteller", out);
                                hersteller = false;
                            } else {
                                String marcrelator = "tld";
                                if (value.endsWith(" [Redaktion]")) {
                                    marcrelator = "red";
                                    value = value.substring(0, value.length() - " [Redaktion]".length());
                                }
                                if (value.startsWith("Red.: ")) {
                                    marcrelator = "red";
                                    value = value.substring("Red.: ".length());
                                }
                                writePerson(herstellerOrRegie, marcrelator, marcrelator.equals("red") ? "Redaktion" : "Regie", out);
                            }
                        }
                    }
                break;
                case "Titel":
                    writeMetadata("TitleDocMain", value, out);
                break;
                case "Ordnungsbegriff":
                    for (String keyword : value.split("; "))
                        writeMetadata("orff_keyword", keyword, out);
                break;
                case "Inhaltsbeschreibung":
                    writeMetadata("description", value, out);
                break;
                case "Archivexemplar":
                    if (value.equals("True"))
                        writeMetadata("orff_exemplar", "Archivexemplar", out);
                break;
                case "Präsenzexemplar":
                    if (value.equals("True"))
                        writeMetadata("orff_exemplar", "Präsenzexemplar", out);
                break;
                case "Medium":
                    writeMetadata("orff_type", "Video", out);
                    writeMetadata("orff_mediatype", value, out);
                break;
                case "Solist/in 1 (Rolle/Instrument/Stimmfach)":
                case "Solist/in 2 (Rolle/Instrument/Stimmfach)":
                case "Solist/in 3 (Rolle/Instrument/Stimmfach)":
                case "Solist/in 4 (Rolle/Instrument/Stimmfach)":
                case "Solist/in 5 (Rolle/Instrument/Stimmfach)":
                case "Solist/in 6 (Rolle/Instrument/Stimmfach)":
                case "Solist/in 7 (Rolle/Instrument/Stimmfach)":
                case "Solist/in 8 (Rolle/Instrument/Stimmfach)":
                case "Solist/in 9 (Rolle/Instrument/Stimmfach)":
                case "Solist/in 10 (Rolle/Instrument/Stimmfach)":
                case "Solist/in 11 (Rolle/Instrument/Stimmfach)":
                case "Solist/in 12 (Rolle/Instrument/Stimmfach)":
                case "Solist/in 13 (Rolle/Instrument/Stimmfach)":
                case "Solist/in 14 (Rolle/Instrument/Stimmfach)":
                case "Solist/in 15 (Rolle/Instrument/Stimmfach)":
                    String[] cellValues = value.split("; ");
                    if (cellValues.length == 2) {
                        String marcrelator = "oth";
                        switch (cellValues[1]) {
                            case "1. Kohorte":
                            case "1. Strolch":
                            case "2. Kohorte":
                            case "2. Strolch":
                            case "3. Strolch":
                            case "Albrecht":
                            case "Alte Hexe":
                            case "Bernauerin":
                            case "Bürger von Munichen":
                            case "Der Bauer":
                            case "Der Kanzler":
                            case "Der Kerkermeister":
                            case "Der Mann mit dem Esel":
                            case "Der Mann mit dem Maulesel":
                            case "Ein Badegast":
                            case "Ein Mönch":
                            case "Erdmutter":
                            case "Hexe":
                            case "Hirte":
                            case "Kaspar Bernauer":
                            case "König":
                            case "Orff":
                            case "Liselotte":
                            case "Seine Tochter":
                            case "Teufel":
                                marcrelator = "prf";
                            break;
        
                            case "Bariton":
                            case "Knabensopran":
                            case "Sopran":
                            case "Tenor":
                                marcrelator = "sng";
                            break;
        
                            case "Dolmetscher":
                                marcrelator = "trl";
                            break;
        
                            case "Klavier":
                                marcrelator = "itr";
                            break;
        
                            case "Sprecher":
                            case "Sprecherin":
                                marcrelator = "spk";
                            break;
                        }
                        writePerson(cellValues[0], marcrelator, cellValues[1], out);
                    } else {
                        writePerson(value, "oth", null, out);
                    }
                break;
                case "Chor":
                    for (String oneChoir : value.split("; ")) {
                        // writeInstitution(oneChoir, "sng", "Chor", out);
                        writeMetadata("orff_choir", oneChoir, out);
                    }
                break;
                case "Choreinstudierung":
                    for (String onePerson : value.split("; ")) {
                        writePerson(onePerson, "tch", "Choreinstudierung", out);
                    }
                break;
                case "Orchester":
                    // writeInstitution(value, "mus", "Orchester", out);
                    writeMetadata("orff_orchestra", value, out);
                break;
                case "Dirigent":
                    writePerson(value, "cnd", "Dirigent", out);
                break;
                case "Inszenierung":
                    for (String ins : value.split("; ")) {
                        writePerson(ins, "sgd", "Inszenierung", out);
                    }
                break;
                case "Bühnenbild":
                    writePerson(value, "std", "Bühnenbild", out);
                break;
                case "Kostüme":
                    writePerson(value, "cst", "Kostüme", out);
                break;
                case "Dauer":
                    writeMetadata("orff_total_time", value, out);
                break;
                case "Technik":
                    writeMetadata("orff_material", value, out);
                break;
                case "Herstellungsjahr":
                    if (!value.equals("?")) writeMetadata("orff_recording_year", value, out);
                break;
                case "Herstellungsort":
                    writeMetadata("orff_recording_place", value, out);
                    break;
                case "Produktions-Nummer":
                    writeMetadata("orff_number", value, out);
                    break;
                case "production right (p)- Jahr":
                    writeMetadata("PublicationYear", value, out);
                    break;
                case "production right (p) - Firma":
                    writeInstitution(value, "cph", "production right (p) - Firma", out);
                break;
                case "Sendequelle/Umschnittquelle":
                    writeMetadata("orff_source", value, out);
                break;
                case "Sendedatum/Umschnittdatum":
                    writeMetadata("date", value, out);
                break;
                case "Umschnittherstellung: Name":
                    writeInstitution(value, "acp", "Umschnittherstellung", out);
                break;
                case "Eigentumsvermerk":
                    if (!value.isEmpty()) {
                        String mappedValue = value;
                        if (value.equals("Carl Orff-Stiftung")) mappedValue = "Carl-Orff-Stiftung";
                        else if (value.equals("OZM")) mappedValue = "Orff-Zentrum München";
                        writeMetadata("ownership", mappedValue, out);
                    }
                break;
                case "Zugangsdatum":
                    writeMetadata("orff_inventory_date", value, out);
                break;
                case "Herkunft":
                    writeMetadata("slub_Provenience", value, out);
                break;
                case "Zugangsdatum für OZM":
                    writeMetadata("orff_inventory_date", value, out);
                break;
                case "Archivnummer":
                    writeMetadata("orff_order_number", value, out);
                break;
                case "Einschränkungen":
                    writeMetadata("accessRestriction", value, out);
                break;
                case "Bemerkungen":
                    writeMetadata("Remarks", value, out);
                break;
                case "Verleih/ Name-Datum":
                    writeInstitution(value, "dst", "Verleih", out);
                break;
                case "Angaben zur Digitalisierung":
                    writeMetadata("orff_location_signature", value, out);
                    break;
                default:
                    // unmapped
                break;
            }
        }

        // collections
        writeMetadata("singleDigCollection", "Bild- und Tonträger", out);
        writeMetadata("singleDigCollection", "Bild- und Tonträger.Video", out);
        return processTitle;
    }

    static void writeMetadata(String key, String value, PrintStream out) {
        if (!value.isEmpty()) out.println("                <kitodo:metadata name=\"" + key + "\">" + value + "</kitodo:metadata>");
    }

    static void writePerson(String name, String roleCode, String roleTextFree, PrintStream out) {
        Matcher mat = LASTNAME_FIRSTNAME.matcher(name);
        if (mat.matches()) {
            out.println("                <kitodo:metadataGroup name=\"Person\">");
            out.println("                    <kitodo:metadata name=\"RoleCode\">" + roleCode + "</kitodo:metadata>");
            if (roleTextFree != null)
                out.println("                    <kitodo:metadata name=\"RoleTextFree\">" + roleTextFree + "</kitodo:metadata>");
            out.println("                    <kitodo:metadata name=\"DisplayForm\">" + mat.group(1) + "</kitodo:metadata>");
            out.println("                    <kitodo:metadata name=\"LastName\">" + mat.group(2) + "</kitodo:metadata>");
            out.println("                    <kitodo:metadata name=\"FirstName\">" + mat.group(3) + "</kitodo:metadata>");
            out.println("                </kitodo:metadataGroup>");
        } else if (!name.isEmpty()) {
            if (name.split(" ").length == 2) {
                String[] nameSplit = name.split(" ");
                out.println("                <kitodo:metadataGroup name=\"Person\">");
                out.println("                    <kitodo:metadata name=\"RoleCode\">" + roleCode + "</kitodo:metadata>");
                if (roleTextFree != null)
                    out.println("                    <kitodo:metadata name=\"RoleTextFree\">" + roleTextFree + "</kitodo:metadata>");
                out.println("                    <kitodo:metadata name=\"DisplayForm\">" + name + "</kitodo:metadata>");
                out.println("                    <kitodo:metadata name=\"FirstName\">" + nameSplit[0] + "</kitodo:metadata>");
                out.println("                    <kitodo:metadata name=\"LastName\">" + nameSplit[1] + "</kitodo:metadata>");
                out.println("                </kitodo:metadataGroup>");
            } else {
                out.println("                <kitodo:metadataGroup name=\"Person\">");
                out.println("                    <kitodo:metadata name=\"RoleCode\">" + roleCode + "</kitodo:metadata>");
                if (roleTextFree != null)
                    out.println("                    <kitodo:metadata name=\"RoleTextFree\">" + roleTextFree + "</kitodo:metadata>");
                out.println("                    <kitodo:metadata name=\"DisplayForm\">" + name + "</kitodo:metadata>");
                out.println("                </kitodo:metadataGroup>");
            }
        }
    }

    static void writeInstitution(String displayForm, String roleCode, String roleTextFree, PrintStream out) {
        if (!displayForm.isEmpty()) {
            out.println("                <kitodo:metadataGroup name=\"Institution\">");
            out.println("                    <kitodo:metadata name=\"RoleCode\">" + roleCode + "</kitodo:metadata>");
            out.println("                    <kitodo:metadata name=\"RoleTextFree\">" + roleTextFree + "</kitodo:metadata>");
            out.println("                    <kitodo:metadata name=\"DisplayForm\">" + displayForm + "</kitodo:metadata>");
            out.println("                </kitodo:metadataGroup>");
        }
    }
}
