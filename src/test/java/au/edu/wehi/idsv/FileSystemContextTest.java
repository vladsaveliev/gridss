package au.edu.wehi.idsv;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;


public class FileSystemContextTest {
	@Rule
    public TemporaryFolder testFolder = new TemporaryFolder();
	public FileSystemContext C() {
		return new FileSystemContext(testFolder.getRoot(), 1);
	}
	@Test
	public void getInsertSizeMetrics_should_use_empty_suffix() {
		assertEquals(new File("src/test/resources/test.bam.gridss.working/test.bam.insert_size_metrics").getAbsolutePath(), C().getInsertSizeMetrics(new File("src/test/resources/test.bam")).getAbsolutePath());
	}
	@Test
	public void getIdsvMetrics_should_use_empty_suffix() {
		assertEquals(new File("src/test/resources/test.bam.gridss.working/test.bam.idsv_metrics").getAbsolutePath(), C().getIdsvMetrics(new File("src/test/resources/test.bam")).getAbsolutePath());
	}
	@Test
	public void get_should_treat_working_files_as_belonging_to_their_parent() {
		assertEquals(new File("src/test/resources/test.bam.gridss.working/test.bam.idsv_metrics").getAbsolutePath(), C().getIdsvMetrics(new File("src/test/resources/test.bam.gridss.working/test.bamsv.bam")).getAbsolutePath());
	}
	private void testFileAssertMatch(String expected, File result) {
		assertEquals(new File(expected).getAbsolutePath(), result.getAbsolutePath());
	}
	private static final File TEST_BAM = new File("src/test/resources/test.bam.gridss.working/test.bam");
	@Test
	public void should_match_constant() {
		testFileAssertMatch("src/test/resources/test.bam.gridss.working/test.bam.idsv_metrics", C().getIdsvMetrics(TEST_BAM));
		testFileAssertMatch("src/test/resources/test.bam.gridss.working/test.bam.insert_size_metrics", C().getInsertSizeMetrics(TEST_BAM));
		testFileAssertMatch("src/test/resources/test.bam.gridss.working/test.bam.realign.0.fq", C().getRealignmentFastq(TEST_BAM, 0));
		testFileAssertMatch("src/test/resources/test.bam.gridss.working/test.bam.realign.0.bam", C().getRealignmentBam(TEST_BAM, 0));
		testFileAssertMatch("src/test/resources/test.bam.gridss.working/test.bam.breakpoint.vcf", C().getBreakpointVcf(TEST_BAM));
	}
	@Test
	public void should_use_working_directory_if_set() throws IOException {
		File working = testFolder.newFolder("workingdir");
		File f = testFolder.newFile("test.bam");
		FileSystemContext fsc = new FileSystemContext(testFolder.getRoot(), working, 1);
		assertTrue(fsc.getIdsvMetrics(f).toString().contains("workingdir"));
	}
}
