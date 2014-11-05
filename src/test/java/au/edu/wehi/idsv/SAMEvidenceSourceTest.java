package au.edu.wehi.idsv;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import htsjdk.samtools.SAMFileHeader.SortOrder;
import htsjdk.samtools.SAMRecord;

import java.util.Collections;
import java.util.EnumSet;
import java.util.List;

import org.junit.Test;

import au.edu.wehi.idsv.util.AsyncBufferedIteratorTest;

import com.google.common.collect.Iterators;
import com.google.common.collect.Lists;

public class SAMEvidenceSourceTest extends IntermediateFilesTest {
	@Test
	public void per_chr_iterator_should_return_all_evidence() {
		createInput(new SAMRecord[] { Read(1, 1, "50M50S") },
				RP(0, 200, 100), // max frag size
				DP(1, 1, "100M", true, 2, 5, "100M", true),
			   DP(1, 2, "100M", true, 2, 4, "100M", true),
			   DP(1, 3, "100M", true, 2, 6, "100M", true),
			   OEA(1, 4, "100M", false));
		SAMEvidenceSource source = new SAMEvidenceSource(getCommandlineContext(true), input, false);
		source.completeSteps(ProcessStep.ALL_STEPS);
		List<DirectedEvidence> list = Lists.newArrayList(source.iterator());
		assertEquals(8, list.size()); // 1 SC + 3 * 2 DP + 1 OEA
	}
	@Test
	public void should_stop_metric_calculation_after_max_records() {
		ProcessingContext pc = getCommandlineContext(true);
		pc.setCalculateMetricsRecordCount(2);
		createInput(RP(0, 100, 200, 100), RP(0, 400, 600, 100));
		SAMEvidenceSource source = new SAMEvidenceSource(pc, input, false);
		source.completeSteps(EnumSet.of(ProcessStep.CALCULATE_METRICS));
		assertEquals(200-100+100, source.getMaxConcordantFragmentSize());
		
		pc.getFileSystemContext().getIdsvMetrics(input).delete();
		pc.getFileSystemContext().getInsertSizeMetrics(input).delete();
		pc.setCalculateMetricsRecordCount(1000);
		source = new SAMEvidenceSource(pc, input, false);
		source.completeSteps(EnumSet.of(ProcessStep.CALCULATE_METRICS));
		assertEquals(600-400+100, source.getMaxConcordantFragmentSize());
	}
	@Test
	public void per_chr_iterator_should_iterator_over_chr_in_dictionary_order() {
		createInput(
				Read(0, 1, "50M50S"),
				Read(1, 1, "50M50S"),
				Read(2, 1, "50M50S"),
				Read(3, 1, "50M50S"),
				Read(4, 1, "50M50S")
				);
		SAMEvidenceSource source = new SAMEvidenceSource(getCommandlineContext(true), input, false);
		source.completeSteps(ProcessStep.ALL_STEPS);
		List<DirectedEvidence> list = Lists.newArrayList(source.iterator());
		for (int i = 0; i <= 4; i++) {
			assertEquals(i, list.get(i).getBreakendSummary().referenceIndex);
		}
	}
	@Test
	public void per_chr_iterator_chr_should_return_only_evidence() {
		createInput(
				Read(0, 1, "50M50S"),
				Read(1, 1, "50M50S"),
				Read(2, 1, "50M50S"),
				Read(3, 1, "50M50S"),
				Read(4, 1, "50M50S")
				);
		SAMEvidenceSource source = new SAMEvidenceSource(getCommandlineContext(true), input, false);
		source.completeSteps(ProcessStep.ALL_STEPS);
		List<DirectedEvidence> list = Lists.newArrayList(source.iterator("polyACGT"));
		assertEquals(1, list.size());
		assertEquals(1, list.get(0).getBreakendSummary().referenceIndex);
	}
	@Test
	public void iterator_should_return_all_evidence() {
		createInput(new SAMRecord[] { Read(1, 1, "50M50S") },
				RP(0, 100, 200, 100),
				DP(1, 1, "100M", true, 2, 5, "100M", true),
			   DP(1, 2, "100M", true, 2, 4, "100M", true),
			   DP(1, 3, "100M", true, 2, 6, "100M", true),
			   OEA(1, 4, "100M", false));
		SAMEvidenceSource source = new SAMEvidenceSource(getCommandlineContext(), input, false);
		source.completeSteps(ProcessStep.ALL_STEPS);
		List<DirectedEvidence> list = Lists.newArrayList(source.iterator());
		assertEquals(8, list.size()); // 1 SC + 3 * 2 DP + 1 OEA
	}
	@Test
	public void iterator_should_iterator_over_chr_in_dictionary_order() {
		createInput(
				Read(0, 1, "50M50S"),
				Read(1, 1, "50M50S"),
				Read(2, 1, "50M50S"),
				Read(3, 1, "50M50S"),
				Read(4, 1, "50M50S")
				);
		SAMEvidenceSource source = new SAMEvidenceSource(getCommandlineContext(), input, false);
		source.completeSteps(ProcessStep.ALL_STEPS);
		List<DirectedEvidence> list = Lists.newArrayList(source.iterator());
		for (int i = 0; i <= 4; i++) {
			assertEquals(i, list.get(i).getBreakendSummary().referenceIndex);
		}
	}
	@Test
	public void iterator_chr_should_return_only_evidence() {
		createInput(
				Read(0, 1, "50M50S"),
				Read(1, 1, "50M50S"),
				Read(2, 1, "50M50S"),
				Read(3, 1, "50M50S"),
				Read(4, 1, "50M50S")
				);
		SAMEvidenceSource source = new SAMEvidenceSource(getCommandlineContext(), input, false);
		source.completeSteps(ProcessStep.ALL_STEPS);
		List<DirectedEvidence> list = Lists.newArrayList(source.iterator("polyACGT"));
		assertEquals(1, list.size());
		assertEquals(1, list.get(0).getBreakendSummary().referenceIndex);
	}
	@Test
	public void should_set_evidence_source_to_self() {
		createInput(Read(0, 1, "50M50S"));
		SAMEvidenceSource source = new SAMEvidenceSource(getCommandlineContext(), input, false);
		source.completeSteps(ProcessStep.ALL_STEPS);
		List<DirectedEvidence> list = Lists.newArrayList(source.iterator());
		assertEquals(1, list.size());
		assertEquals(source, list.get(0).getEvidenceSource());
	}
	@Test
	public void should_default_fragment_size_to_read_length_for_unpaired_reads() {
		createInput(Read(0, 1, "50M50S"));
		SAMEvidenceSource source = new SAMEvidenceSource(getCommandlineContext(), input, false);
		source.completeSteps(ProcessStep.ALL_STEPS);
		assertEquals(100, source.getMaxConcordantFragmentSize());
	}
	@Test
	public void iterator_evidence_should_be_sorted_by_evidence_natural_ordering() {
		createInput(new SAMRecord[] { Read(1, 1, "50M50S") },
				new SAMRecord[] { Read(1, 1, "50S50M") },
				RP(0, 100, 200, 100),
				DP(1, 1, "100M", true, 2, 5, "100M", true),
			   DP(1, 2, "100M", false, 2, 4, "100M", true),
			   DP(1, 3, "100M", true, 2, 6, "100M", true),
			   OEA(1, 4, "100M", false),
			   OEA(1, 4, "100M", true));
		SAMEvidenceSource source = new SAMEvidenceSource(getCommandlineContext(), input, false);
		source.completeSteps(ProcessStep.ALL_STEPS);
		List<DirectedEvidence> result = Lists.newArrayList(source.iterator());
		List<DirectedEvidence> sorted = Lists.newArrayList(result);
		Collections.sort(sorted, DirectedEvidenceOrder.ByNatural);
		assertEquals(sorted, result);
	}
	@Test
	public void iterator_should_match_soft_clip_realignment_with_soft_clip() {
		createInput(
				withReadName("r1", Read(0, 1, "15M15S")),
				withReadName("r2", Read(1, 2, "15M15S")),
				withReadName("r3", Read(2, 3, "15M15S")));
		SAMEvidenceSource source = new SAMEvidenceSource(getCommandlineContext(), input, false);
		source.completeSteps(ProcessStep.ALL_STEPS);
		createBAM(getCommandlineContext().getFileSystemContext().getRealignmentBam(input), SortOrder.unsorted, 
				withReadName("0#1#fr1", Read(2, 10, "15M"))[0],
				withReadName("1#2#fr2", Read(1, 10, "15M"))[0],
				withReadName("2#3#fr3", Read(0, 10, "15M"))[0]
		);
		List<DirectedEvidence> result = Lists.newArrayList(source.iterator());
		assertEquals(3, result.size());
		assertTrue(result.get(0) instanceof RealignedSoftClipEvidence);
		assertTrue(result.get(1) instanceof RealignedSoftClipEvidence);
		assertTrue(result.get(2) instanceof RealignedSoftClipEvidence);
	}
	@Test
	public void iterator_should_iterator_over_both_forward_and_backward_soft_clips() {
		createInput(withReadName("r1", Read(0, 1, "15S15M15S")));
		SAMEvidenceSource source = new SAMEvidenceSource(getCommandlineContext(), input, false);
		source.completeSteps(ProcessStep.ALL_STEPS);
		
		List<DirectedEvidence> result = Lists.newArrayList(source.iterator());
		assertEquals(2, result.size());
	}
	@Test
	public void iterator_should_realign_both_forward_and_backward_soft_clips() {
		createInput(
				withReadName("r1", Read(0, 1, "15S15M15S")),
				withReadName("r2", Read(1, 2, "15S15M15S")),
				withReadName("r3", Read(2, 3, "15S15M15S")));
		SAMEvidenceSource source = new SAMEvidenceSource(getCommandlineContext(), input, false);
		source.completeSteps(ProcessStep.ALL_STEPS);
		createBAM(getCommandlineContext().getFileSystemContext().getRealignmentBam(input), SortOrder.unsorted, 
				withReadName("0#1#fr1", Read(2, 10, "15M"))[0],
				withReadName("0#1#br1", Read(1, 15, "15M"))[0],
				withReadName("1#2#fr2", Read(1, 10, "15M"))[0],
				withReadName("2#3#fr3", Read(0, 10, "15M"))[0],
				withReadName("2#3#br3", Read(0, 100, "15M"))[0]
		);
		List<RealignedSoftClipEvidence> result = Lists.newArrayList(Iterators.filter(source.iterator(), RealignedSoftClipEvidence.class));
		
		assertEquals(5, result.size());
	}
	@Test
	public void iterator_should_include_remote_soft_clips() {
		createInput(
				withReadName("r1", Read(0, 1, "15S15M15S")),
				withReadName("r2", Read(1, 2, "15S15M15S")),
				withReadName("r3", Read(2, 3, "15S15M15S")));
		SAMEvidenceSource source = new SAMEvidenceSource(getCommandlineContext(), input, false);
		source.completeSteps(ProcessStep.ALL_STEPS);
		createBAM(getCommandlineContext().getFileSystemContext().getRealignmentBam(input), SortOrder.unsorted, 
				withReadName("0#1#fr1", Read(2, 10, "15M"))[0],
				withReadName("0#1#br1", Read(1, 15, "15M"))[0],
				withReadName("1#2#fr2", Read(1, 10, "15M"))[0],
				withReadName("2#3#fr3", Read(0, 10, "15M"))[0],
				withReadName("2#3#br3", Read(0, 100, "15M"))[0]);
		source.completeSteps(ProcessStep.ALL_STEPS);
		
		List<RealignedRemoteSoftClipEvidence> remote = Lists.newArrayList(Iterators.filter(source.iterator(), RealignedRemoteSoftClipEvidence.class));
		assertEquals(5, remote.size());
		
		List<RealignedSoftClipEvidence> result = Lists.newArrayList(Iterators.filter(source.iterator(), RealignedSoftClipEvidence.class));
		assertEquals(5, result.size() - remote.size());
	}
	@Test
	public void should_construct_percentage_based_calculator() {
		createInput(
				RP(0, 100, 110, 5), // 15
				RP(0, 100, 111, 5), 
				RP(0, 100, 112, 5), // --- 17
				RP(0, 100, 113, 5), // ---
				RP(0, 100, 114, 5), // --- concordant 
				RP(0, 100, 115, 5), // --- reads
				RP(0, 100, 116, 5), // ---
				RP(0, 100, 117, 5), // --- 22
				RP(0, 100, 118, 5),
				RP(0, 100, 119, 5) // 24
				);
		SAMEvidenceSource source = new SAMEvidenceSource(getCommandlineContext(), input, false, 0.59);
		source.completeSteps(ProcessStep.ALL_STEPS);
		
		assertEquals(PercentageReadPairConcordanceCalculator.class, source.getReadPairConcordanceCalculator().getClass());
		assertEquals(22, source.getMaxConcordantFragmentSize());
		assertEquals(5, source.getMaxReadLength());
		
		List<DirectedEvidence> list = Lists.newArrayList(source.iterator());
		assertEquals(4 * 2, list.size()); // 4 discordant pairs
	}
	@Test
	public void should_construct_fixed_calculator() {
		createInput(
				RP(0, 1, 12, 1), // 12
				RP(0, 1, 13, 1), // 13 conc
				RP(0, 1, 14, 1), // 14 conc
				RP(0, 1, 15, 1), // 15 conc
				RP(0, 1, 16, 1)); // 16
		SAMEvidenceSource source = new SAMEvidenceSource(getCommandlineContext(), input, false, 13, 15);
		source.completeSteps(ProcessStep.ALL_STEPS);
		
		assertEquals(FixedSizeReadPairConcordanceCalculator.class, source.getReadPairConcordanceCalculator().getClass());
		assertEquals(15, source.getMaxConcordantFragmentSize());
		assertEquals(1, source.getMaxReadLength());
		List<DirectedEvidence> list = Lists.newArrayList(source.iterator());
		assertEquals(2 * 2, list.size());
	}
	@Test
	public void closing_iterator_should_close_underlying_resources() {
		createInput(RP(0, 1, 12, 1));
		int pre = AsyncBufferedIteratorTest.getFirstThreadWithNamePrefixCount("AsyncBufferedIterator");
		SAMEvidenceSource source = new SAMEvidenceSource(getCommandlineContext(), input, false, 13, 15);
		source.completeSteps(ProcessStep.ALL_STEPS);
		assertEquals(pre, AsyncBufferedIteratorTest.getFirstThreadWithNamePrefixCount("AsyncBufferedIterator"));
	}
}