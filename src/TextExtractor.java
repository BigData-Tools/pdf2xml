import java.io.IOException;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.sql.rowset.spi.XmlWriter;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.Result;
import javax.xml.transform.Source;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerConfigurationException;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.TransformerFactoryConfigurationError;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;

import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.graphics.PDGraphicsState;
import org.apache.pdfbox.pdmodel.graphics.color.PDColorState;
import org.apache.pdfbox.util.PDFTextStripper;
import org.apache.pdfbox.util.TextPosition;
import org.w3c.dom.CDATASection;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

public class TextExtractor extends PDFTextStripper {
	
	private ArrayList<TextRun> textRuns;
	
	private HashMap<Float, ArrayList<TextRun>> yPosMap;
	
	public TextExtractor() throws IOException {
		super();
		textRuns = new ArrayList<TextRun>();
		yPosMap = new HashMap<Float, ArrayList<TextRun>>();
	}
	
	@Override
	public void processStream(PDPage aPage, PDResources resources,
			COSStream cosStream) throws IOException {
		super.processStream(aPage, resources, cosStream);
		coalesceRows();
	}

	protected void processTextPosition(TextPosition tp) {
		PDGraphicsState gs = getGraphicsState();
		
		// try to find an existing text run that fits
		boolean added = false;
		
		for (TextRun tr : textRuns) {
			if (tr.hasMatchingStyle(tp, gs)) {
				if (tr.isIncidentToLeft(tp)) {
					tr.addBefore(tp.getCharacter());
					added = true;
					break;
				} else if (tr.isIncidentToRight(tp)) {
					tr.addAfter(tp.getCharacter());
					added = true;
					break;
				}
			}
		}
		
		if (!added) {
			TextRun newTr = TextRun.newFor(tp, gs);
			textRuns.add(newTr);
			
			ArrayList<TextRun> withSameY = yPosMap.get(newTr.y);
			if (withSameY == null) {
				ArrayList<TextRun> newWithSameY = new ArrayList<TextRun>();
				newWithSameY.add(newTr);
				yPosMap.put(newTr.y, newWithSameY);
			} else {
				withSameY.add(newTr);
			}
		}
	}
	
	public void coalesceRows() {
		for (Float f : yPosMap.keySet()) {
			ArrayList<TextRun> runs = yPosMap.get(f);
			
			Collections.sort(runs);
			
			int i=0;
			while (i+1 < runs.size()) {
				TextRun first = runs.get(i);
				TextRun snd = runs.get(i+1);
				
				if (first.hasMatchingStyle(snd) 
						/*&& first.isIncidentToRight(snd)*/) {
					first.addAfter(snd);
					runs.remove(i+1);
					textRuns.remove(snd);
				} else {
					i++;
				}
			}
		}
	}
	
	public String toString() {
		String s = "";
		for (TextRun tr : textRuns) {
			try {
				s += tr.run + " @ " + tr.x + "," + tr.y 
							+ " w " + tr.width
							+ " : " + tr.font.getBaseFont() 
							+ " " + tr.pointSize + "pt"
							+ " C " + tr.strokeColor.getJavaColor()
							+ "\n";
			} catch (IOException e) {
				
			}
		}
		return s;
	}
	
	public String toXml() {
		String r = "";
		
		try {
			DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
			DocumentBuilder b = f.newDocumentBuilder();
			
			Document doc = b.newDocument();
			Element pdf2xml = doc.createElement("pdf2xml");
			doc.appendChild(pdf2xml);
			
			for (TextRun tr : textRuns) {
				Element text = doc.createElement("text");
				text.setAttribute("top", String.valueOf(tr.y));
				text.setAttribute("left", String.valueOf(tr.x));
				text.setAttribute("width", String.valueOf(tr.width));
				text.setAttribute("size", String.valueOf(tr.pointSize));
				text.setAttribute("family", tr.getFontFamily());
				text.setAttribute("face", tr.getFontFace());
				text.setAttribute("color", tr.getForegroundColor());
				text.setAttribute("bgcolor", tr.getBackgroundColor());
				
				CDATASection cdata = doc.createCDATASection(tr.run);
				
				pdf2xml.appendChild(text);
				text.appendChild(cdata);
			}
			
			Source source = new DOMSource(doc);
			StringWriter sw = new StringWriter();
			Result result = new StreamResult(sw);
			
			Transformer xformer = TransformerFactory.newInstance().newTransformer();
			xformer.transform(source, result);
			
			return sw.toString();
		} catch (TransformerConfigurationException e) {
			e.printStackTrace();
		} catch (TransformerFactoryConfigurationError e) {
			e.printStackTrace();
		} catch (TransformerException e) {
			e.printStackTrace();
		} catch (ParserConfigurationException e) {
			e.printStackTrace();
		}
        
		return r;
	}
}

class TextRun implements Comparable<TextRun> {
	float x;
	float y;
	float width;
	float pointSize;
	String run;
	PDFont font;
	PDColorState strokeColor;
	PDColorState nonStrokeColor;
	
	static TextRun newFor(TextPosition tp, PDGraphicsState gs) {
		TextRun tr = new TextRun();
		tr.x = tp.getX();
		tr.y = tp.getY();
		tr.font = tp.getFont();
		tr.strokeColor = gs.getStrokingColor();
		tr.nonStrokeColor = gs.getNonStrokingColor();
		tr.run = tp.getCharacter();
		tr.width = tp.getWidth();
		tr.pointSize = tp.getFontSizeInPt();
		 
		return tr;
	}
	
	private static float looseness() {
		return 1;
	}
	
	private static float looseness(TextPosition tp, PDFont font) {
		return tp.getWidth() / 2;
	}
	
	private static String getColorString(PDColorState c) {
		float[] vals = c.getColorSpaceValue();
		if (vals.length == 1) {
			return "rgb(" + vals[0] + ", " + vals[0] + ", " + vals[0] + ")";
		}
		return "rgb(" + vals[0] + ", " + vals[1] + ", " + vals[2] + ")";
	}
	
	String getForegroundColor() {
		return getColorString(strokeColor);
	}
	
	String getBackgroundColor() {
		return getColorString(nonStrokeColor);
	}
	
	String getFontFamily() {
		String fontName = font.getBaseFont();
		String[] bits = fontName.split("\\+|-");
		if (bits.length > 1) {
			return bits[1];
		}
		return "";
	}
	
	String getFontFace() {
		String fontName = font.getBaseFont();
		String[] bits = fontName.split("\\+|-");
		if (bits.length > 2) {
			return bits[2];
		}
		return "Normal";
	}
	
	TextRun addBefore(TextRun tr) {
		run = tr.run + run;
		return this;
	}
	
	TextRun addAfter(TextRun tr) {
		run += tr.run;
		return this;
	}
	
	TextRun addBefore(String s) {
		run = s + run;
		
		try {
			width = font.getStringWidth(run);
		} catch (IOException e) {
			Logger.getAnonymousLogger()
			      .log(Level.WARNING, "Can't update TextRun width.");
		}
		
		return this;
	}
	
	TextRun addAfter(String s) {
		run += s;
		
		try {
			width = font.getStringWidth(run);
		} catch (IOException e) {
			Logger.getAnonymousLogger()
				  .log(Level.WARNING, "Can't update TextRun width.");
		}
		
		return this;
	}
	
	boolean isIncidentToLeft(TextRun tr) {
		final float runRightX = tr.x + tr.width;
		
		return y == tr.y
				&& runRightX >= (x - looseness())
				&& runRightX <= x + looseness();
	}
	
	boolean isIncidentToRight(TextRun tr) {
		return y == tr.y
				&& tr.x >= (x + width - looseness())
				&& tr.x <= (x + width + looseness());
	}
	
	boolean isIncidentToLeft(TextPosition tp) {
		final float mostAcceptableLeft = x - looseness(tp, font);
		final float mostAcceptableRight = x;
		final float charRightX = tp.getX() + tp.getWidth();
		
		return y == tp.getY() 
				&& charRightX >= mostAcceptableLeft
				&& charRightX <= mostAcceptableRight;
	}
	
	boolean isIncidentToRight(TextPosition tp) {
		final float mostAcceptableLeft = x + width;
		final float mostAcceptableRight = x + width + looseness(tp, font);
		
		return y == tp.getY()
				&& tp.getX() >= mostAcceptableLeft
				&& tp.getX() <= mostAcceptableRight;
	}
	
	boolean hasMatchingStyle(TextPosition tp, PDGraphicsState gs) {
		return tp.getFont() == font
				&& gs.getStrokingColor() == strokeColor
				&& gs.getNonStrokingColor() == nonStrokeColor;
	}
	
	boolean hasMatchingStyle(TextRun tr) {
		return tr.font == font
				&& tr.strokeColor == strokeColor
				&& tr.nonStrokeColor == nonStrokeColor;
	}

	@Override
	public int compareTo(TextRun other) {
		if (this.x < other.x) {
			return -1;
		} else if (this.x == other.x) {
			return 0;
		} else {
			return 1;
		}
	}
}
