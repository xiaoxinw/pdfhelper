package com.zjzc.pdfhelper;

import java.io.*;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLConnection;
import java.nio.file.Paths;
import java.util.List;

import net.sourceforge.argparse4j.impl.Arguments;
import net.sourceforge.argparse4j.inf.ArgumentAction;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.io.IOUtils;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.encryption.AccessPermission;
import org.apache.pdfbox.text.PDFTextStripper;
import net.sourceforge.argparse4j.ArgumentParsers;
import net.sourceforge.argparse4j.inf.ArgumentParser;
import net.sourceforge.argparse4j.inf.Namespace;

public class ExtractText
{
    private String cmap = "";
    private ExtractText()
    {

    }
    public static void main(String[] args) throws IOException
    {
        Namespace ns = parseArgs(args);
        String file = ns.getString("file");
        Integer startPage = ns.getInt("start");
        Integer endPage = ns.getInt("end");
        String out = ns.getString("out");
        Boolean sort = ns.getBoolean("sort");
        String encoding = ns.getString("encoding");
        String cmap = ns.getString("cmap");
        List<Integer> pages = ns.getList("pages");
        ExtractText extractor = new ExtractText();
        extractor.cmap = cmap == "" ? Paths.get("").toAbsolutePath().toString() : cmap;
        extractor.startExtraction(file, out, startPage, endPage, pages, sort, encoding);
    }
    private static Namespace parseArgs(String[] args)
    {
        ArgumentParser parser = ArgumentParsers.newFor("ExtractText").build()
                .defaultHelp(true)
                .description("Extract text from pdf.");
        parser.addArgument("--file").required(true).help("file path or url");
        parser.addArgument("--start").type(Integer.class).setDefault(1).help("start page");
        parser.addArgument("--end").type(Integer.class).setDefault(Integer.MAX_VALUE).help("end page");
        parser.addArgument("--pages").type(Integer.class).nargs("+").help("specific page numbers to extract, override start and end.");
        parser.addArgument("--sort").type(Boolean.class).action(Arguments.storeTrue()).setDefault(false).help("if sort output");
        parser.addArgument("--out").help("outfile");
        parser.addArgument("--encoding").setDefault("UTF-8").help("out encoding");
        parser.addArgument("--cmap").setDefault("").help("absolute path to cmap dictionary");
        Namespace ns = null;
        ns = parser.parseArgsOrFail(args);
        return ns;
    }
    public void startExtraction(String inputFile, String outFile, Integer startPage, Integer endPage, List<Integer> pages,
                                Boolean sort, String encoding) throws IOException
    {
        InputStream input = null;
        Writer output = null;
        try
        {
            try
            {
                URL url = new URL(inputFile);
                URLConnection con = url.openConnection();
                con.setConnectTimeout(5000); // 5 seconds;
                con.setRequestProperty("User-agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/71.0.3578.80 Safari/537.36");
                input = con.getInputStream();
            }
            catch (MalformedURLException e)
            {
                File file = new File(inputFile);
                input = new FileInputStream(file);
            }
            if (outFile == null)
            {
                output = new OutputStreamWriter(System.out, encoding);
            }
            else
            {
                File f = new File(outFile);
                output = new OutputStreamWriter( new FileOutputStream(f), encoding );
            }
            try(PDDocument document = PDDocument.load(input))
            {
//                AccessPermission ap = document.getCurrentAccessPermission();
//                if (!ap.canExtractContent())
//                {
//                    throw new IOException("You do not have permission to extract text");
//                }

                PDFTextStripper stripper = new PDFTextStripper();

                // This example uses sorting, but in some cases it is more useful to switch it off,
                // e.g. in some files with columns where the PDF content stream respects the
                // column order.
                stripper.setSortByPosition(sort);
                if (pages != null)
                {
                    startPage = 1;
                    endPage = document.getNumberOfPages();
                }
                else
                {
                    endPage = Math.min(endPage, document.getNumberOfPages());
                }

                for (int p = startPage; p <= endPage; ++p)
                {
                    if (pages != null && !pages.contains(p))
                    {
                        // skip this page
                        continue;
                    }
                    PDPage page = document.getPage(p-1);

                    fixPageResources(document, page);

                    // Set the page interval to extract. If you don't, then all pages would be extracted.
                    stripper.setStartPage(p);
                    stripper.setEndPage(p);

                    // let the magic happen
                    stripper.writeText(document, output);
                }
            }
        }
        finally
        {
            IOUtils.closeQuietly(input);
            IOUtils.closeQuietly(output);
        }
    }
    private  void fixPageResources(PDDocument doc, PDPage page) throws IOException
    {
        PDResources res = page.getResources();
        for (COSName fontName : res.getFontNames())
        {
            COSDictionary font_dic = (COSDictionary)res.getCOSObject().getDictionaryObject(fontName);
            if (font_dic == null)
            {
                COSDictionary dict = (COSDictionary)res.getCOSObject().getDictionaryObject(COSName.FONT);
                if (dict != null)
                {
                    font_dic = (COSDictionary)dict.getDictionaryObject(fontName);
                }
            }
            if (font_dic == null)
            {
                continue;
            }
            COSBase encoding = font_dic.getDictionaryObject(COSName.ENCODING);
            if (!COSName.IDENTITY_H.equals(encoding)) {
                continue;
            }
            // get real name
            String baseFont = font_dic.getNameAsString("BaseFont");
            int plus = baseFont.indexOf('+');
            if (plus != -1)
            {
                baseFont = baseFont.substring(plus + 1);
            }
            if (font_dic.containsKey(COSName.TO_UNICODE))
            {
                continue;
            }
            String fileName = "to-unicode-" + baseFont;
            COSStream toUnicodeStream = doc.getDocument().createCOSStream();
            URL toUnicodeUrl = getClass().getResource(fileName);
            if (toUnicodeUrl == null)
            {
                File toUnicodeFile = new File(cmap, fileName);
                if (toUnicodeFile.exists())
                {
                    toUnicodeUrl = toUnicodeFile.toURI().toURL();
                }
            }
            if (toUnicodeUrl == null)
            {
                continue;
            }
            InputStream input = null;
            OutputStream output = null;
            try{
                input = toUnicodeUrl.openStream();
                output = toUnicodeStream.createOutputStream(COSName.FLATE_DECODE);
                IOUtils.copy(input, output);
            }
            finally {
                IOUtils.closeQuietly(input);
                IOUtils.closeQuietly(output);
            }
            font_dic.setItem(COSName.TO_UNICODE, toUnicodeStream);
        }
    }
}
