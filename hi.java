

<dependency>
    <groupId>com.itextpdf</groupId>
    <artifactId>itextpdf</artifactId>
    <version>5.5.13.3</version>
</dependency>
<dependency>
    <groupId>com.google.zxing</groupId>
    <artifactId>core</artifactId>
    <version>3.5.1</version>
</dependency>
<dependency>
    <groupId>com.google.zxing</groupId>
    <artifactId>javase</artifactId>
    <version>3.5.1</version>
</dependency>
 
package com.booking.controller;

import com.booking.service.PdfService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@CrossOrigin(origins = "http://localhost:3000")
@RestController
@RequestMapping("/api/v1/pdf")
public class BookingPdfController {

    private final PdfService pdfService;

    public BookingPdfController(PdfService pdfService) {
        this.pdfService = pdfService;
    }

    // On-demand ticket download/view
    @GetMapping("/{bookingId}/ticket")
    public ResponseEntity<byte[]> downloadTicket(@PathVariable Long bookingId) throws Exception {
        byte[] pdf = pdfService.generateBookingPdf(bookingId);

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=booking-" + bookingId + ".pdf")
                .contentType(MediaType.APPLICATION_PDF)
                .contentLength(pdf.length)
                .body(pdf);
    }
}


package com.booking.service;

import com.booking.entity.Booking;
import com.booking.entity.Seat;
import com.booking.entity.Passenger;
import com.booking.repository.BookingRepository;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.WriterException;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.client.j2se.MatrixToImageWriter;
import com.itextpdf.text.*;
import com.itextpdf.text.pdf.*;
import org.springframework.stereotype.Service;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.List;
import java.util.stream.Collectors;

@Service
public class PdfService {

    private final BookingRepository bookingRepository;

    public PdfService(BookingRepository bookingRepository) {
        this.bookingRepository = bookingRepository;
    }

    public byte[] generateBookingPdf(Long bookingId) throws Exception {
        Booking booking = bookingRepository.findByIdWithDetails(bookingId)
                .orElseThrow(() -> new RuntimeException("Booking not found: " + bookingId));

        Document document = new Document(PageSize.A4, 36, 36, 36, 36);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        PdfWriter.getInstance(document, out);
        document.open();

        Font headerFont = new Font(Font.FontFamily.HELVETICA, 20, Font.BOLD, BaseColor.BLUE);
        Font labelFont = new Font(Font.FontFamily.HELVETICA, 12, Font.BOLD, BaseColor.DARK_GRAY);
        Font valueFont = new Font(Font.FontFamily.HELVETICA, 12, Font.NORMAL, BaseColor.BLACK);
        Font tableHeaderFont = new Font(Font.FontFamily.HELVETICA, 11, Font.BOLD);
        Font tableCellFont = new Font(Font.FontFamily.HELVETICA, 10, Font.NORMAL);

        Paragraph header = new Paragraph("🚌 Bus Booking Ticket", headerFont);
        header.setAlignment(Element.ALIGN_CENTER);
        header.setSpacingAfter(10f);
        document.add(header);

        SimpleDateFormat df = new SimpleDateFormat("dd MMM yyyy, HH:mm");
        addLine(document, "Booking ID: ", String.valueOf(booking.getBookingId()), labelFont, valueFont);
        addLine(document, "User ID: ", String.valueOf(booking.getUserId()), labelFont, valueFont);
        addLine(document, "Trip ID: ", String.valueOf(booking.getTripId()), labelFont, valueFont);
        addLine(document, "Booking Date: ", df.format(booking.getBookingDate()), labelFont, valueFont);
        addLine(document, "Status: ", booking.getStatus(), labelFont, valueFont);
        addLine(document, "Total Paid: ", String.format("Rs %.2f", booking.getTotalAmount()), labelFont, valueFont);

        String seatSummary = (booking.getSeats() == null || booking.getSeats().isEmpty()) ? "-" :
                booking.getSeats().stream()
                        .map(s -> String.valueOf(s.getSeatNumber()))
                        .sorted()
                        .collect(Collectors.joining(", "));
        addLine(document, "Seats: ", seatSummary, labelFont, valueFont);

        document.add(new Paragraph("\n"));

        Set<Seat> seats = booking.getSeats();
        if (seats != null && !seats.isEmpty()) {
            Paragraph seatsHeader = new Paragraph("Selected Seats", labelFont);
            seatsHeader.setSpacingAfter(6f);
            document.add(seatsHeader);

            PdfPTable seatsTable = new PdfPTable(3);
            seatsTable.setWidthPercentage(100);
            seatsTable.setSpacingBefore(4f);
            seatsTable.setWidths(new float[]{25, 25, 50});
            addTableHeader(seatsTable, new String[]{"Seat Number", "Type", "Booked"}, tableHeaderFont);

            List<Seat> sortedSeats = new ArrayList<>(seats);
            sortedSeats.sort(Comparator.comparing(Seat::getSeatNumber));

            for (Seat s : sortedSeats) {
                seatsTable.addCell(tableCell(String.valueOf(s.getSeatNumber()), tableCellFont));
                seatsTable.addCell(tableCell(s.getSeatType() != null ? s.getSeatType() : "-", tableCellFont));
                seatsTable.addCell(tableCell(s.isBooked() ? "Yes" : "No", tableCellFont));
            }
            document.add(seatsTable);
        }

// ✅ Convert Set to List for sorting and iteration
        List<Passenger> passengers = new ArrayList<>(booking.getPassengers());

        if (passengers != null && !passengers.isEmpty()) {
            Paragraph paxHeader = new Paragraph("Passengers", labelFont);
            paxHeader.setSpacingBefore(12f);
            paxHeader.setSpacingAfter(6f);
            document.add(paxHeader);

            PdfPTable paxTable = new PdfPTable(4);
            paxTable.setWidthPercentage(100);
            paxTable.setSpacingBefore(4f);
            paxTable.setWidths(new float[]{35, 15, 20, 30});
            addTableHeader(paxTable, new String[]{"Name", "Age", "Gender", "Contact"}, tableHeaderFont);

            // ✅ Optional: Sort passengers by name
            passengers.sort(Comparator.comparing(Passenger::getName));

            for (Passenger p : passengers) {
                paxTable.addCell(tableCell(p.getName() != null ? p.getName() : "-", tableCellFont));
                paxTable.addCell(tableCell(String.valueOf(p.getAge()), tableCellFont));
                paxTable.addCell(tableCell(p.getGender() != null ? p.getGender() : "-", tableCellFont));
                paxTable.addCell(tableCell(p.getContact() != null ? p.getContact() : "-", tableCellFont));
            }
            document.add(paxTable);
        }


        document.add(new Paragraph("\n"));

        String qrData = "Booking ID: " + booking.getBookingId()
                + "\nUser ID: " + booking.getUserId()
                + "\nTrip ID: " + booking.getTripId()
                + "\nStatus: " + booking.getStatus()
                + "\nSeats: " + seatSummary
                + "\nTotal: " + String.format("Rs %.2f", booking.getTotalAmount());
        Image qrImage = generateQrImage(qrData);
        qrImage.scaleAbsolute(120, 120);
        qrImage.setAlignment(Element.ALIGN_CENTER);
        document.add(qrImage);

        document.add(new Paragraph("\n"));

        Paragraph footer = new Paragraph(
                "Thank you for booking with us! Please carry a valid ID proof.\nFor support, contact support@yourapp.com",
                new Font(Font.FontFamily.HELVETICA, 10, Font.ITALIC, BaseColor.GRAY)
        );
        footer.setAlignment(Element.ALIGN_CENTER);
        document.add(footer);

        document.close();
        return out.toByteArray();
    }

    private void addLine(Document doc, String label, String value, Font labelFont, Font valueFont)
            throws DocumentException {
        Phrase phrase = new Phrase();
        phrase.add(new Chunk(label, labelFont));
        phrase.add(new Chunk(value, valueFont));
        Paragraph paragraph = new Paragraph(phrase);
        paragraph.setSpacingAfter(5f);
        doc.add(paragraph);
    }

    private void addTableHeader(PdfPTable table, String[] headers, Font font) {
        for (String h : headers) {
            PdfPCell cell = new PdfPCell(new Phrase(h, font));
            cell.setBackgroundColor(new BaseColor(240, 240, 240));
            cell.setHorizontalAlignment(Element.ALIGN_CENTER);
            cell.setPadding(6f);
            table.addCell(cell);
        }
    }

    private PdfPCell tableCell(String text, Font font) {
        PdfPCell c = new PdfPCell(new Phrase(text, font));
        c.setPadding(6f);
        return c;
    }

    private Image generateQrImage(String data) throws WriterException, java.io.IOException, BadElementException {
        QRCodeWriter qrCodeWriter = new QRCodeWriter();
        BitMatrix bitMatrix = qrCodeWriter.encode(data, BarcodeFormat.QR_CODE, 200, 200);
        BufferedImage bufferedImage = MatrixToImageWriter.toBufferedImage(bitMatrix);

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        javax.imageio.ImageIO.write(bufferedImage, "png", baos);
        return Image.getInstance(baos.toByteArray());
    }
}
 

package com.booking.repository;

import com.booking.entity.Booking;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface BookingRepository extends JpaRepository<Booking, Long> {

    List<Booking> findByTripId(Long tripId);
    List<Booking> findByUserId(Long userId);

    // ✅ Use EntityGraph instead of multiple JOIN FETCH
    @EntityGraph(attributePaths = {"seats", "passengers"})
    @Query("SELECT b FROM Booking b WHERE b.bookingId = :id")
    Optional<Booking> findByIdWithDetails(@Param("id") Long id);
}


 
 
