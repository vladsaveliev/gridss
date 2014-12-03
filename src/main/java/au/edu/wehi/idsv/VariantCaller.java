package au.edu.wehi.idsv;

import htsjdk.samtools.SAMFileHeader.SortOrder;
import htsjdk.samtools.SAMRecord;
import htsjdk.samtools.SAMSequenceDictionary;
import htsjdk.samtools.util.CloseableIterator;
import htsjdk.samtools.util.CloserUtil;
import htsjdk.samtools.util.Log;
import htsjdk.samtools.util.ProgressLogger;
import htsjdk.variant.variantcontext.VariantContext;
import htsjdk.variant.variantcontext.writer.VariantContextWriter;
import htsjdk.variant.vcf.VCFFileReader;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;

import au.edu.wehi.idsv.util.AsyncBufferedIterator;
import au.edu.wehi.idsv.util.AutoClosingMergedIterator;
import au.edu.wehi.idsv.util.FileHelper;
import au.edu.wehi.idsv.validation.TruthAnnotator;
import au.edu.wehi.idsv.vcf.VcfFileUtil;

import com.google.common.base.Function;
import com.google.common.collect.Iterators;
import com.google.common.collect.Lists;


/**
 * Calls structural variant
 * @author cameron.d
 *
 */
public class VariantCaller extends EvidenceProcessorBase {
	private static final Log log = Log.getInstance(VariantCaller.class);
	public VariantCaller(ProcessingContext context, File output, List<SAMEvidenceSource> samEvidence, AssemblyEvidenceSource assemblyEvidence) {
		super(context, output, samEvidence, assemblyEvidence);
	}
	@Override
	public void process() {
		callBreakends(null);
		annotateBreakpoints(null);
		writeOutput();
	}
	private static class WriteMaximalCliquesForChromosome extends EvidenceProcessorBase implements Callable<Void> {
		private int chri;
		private int chrj;
		public WriteMaximalCliquesForChromosome(ProcessingContext context, File output, List<SAMEvidenceSource> samEvidence, AssemblyEvidenceSource assemblyEvidence, int chri, int chrj) {
			super(context, output, samEvidence, assemblyEvidence);
			this.chri = chri;
			this.chrj = chrj;
		}
		@Override
		public Void call() {
			final SAMSequenceDictionary dict = processContext.getReference().getSequenceDictionary();
			final String iname = dict.getSequence(chri).getSequenceName();
			final String jname = dict.getSequence(chrj).getSequenceName();
			final String task = "Identifying Breakpoints between "  + iname + " and " + jname;
			final File outfile = processContext.getFileSystemContext().getBreakpointVcf(output, iname, jname);
			if (outfile.exists()) {
				log.debug("Skipping " + task + ": output file exists");
				return null;
			}
			log.info("Start " + task);
			try {
				CloseableIterator<DirectedEvidence> evidenceIt = null;
				File unsorted = FileSystemContext.getWorkingFileFor(outfile);
				File sorted = FileSystemContext.getWorkingFileFor(outfile, "sorted.");
				unsorted.delete();
				sorted.delete();
				try {
					boolean assemblyOnly = processContext.getVariantCallingParameters().callOnlyAssemblies; 
					evidenceIt = getEvidenceForChr(true, true, !assemblyOnly, !assemblyOnly, !assemblyOnly,
							chri, chrj);
					EvidenceClusterProcessor processor = new EvidenceClusterSubsetProcessor(processContext, evidenceIt, chri, chrj);
					writeMaximalCliquesToVcf(
							processContext,
							new DirectedEvidenceBoundsAssertionIterator<VariantContextDirectedEvidence>(processor, chri, chrj),
							unsorted);
				} finally {
					CloserUtil.close(evidenceIt);
				}
				VcfFileUtil.sort(processContext, unsorted, sorted);
				FileHelper.move(sorted, outfile, true);
				log.info("Complete " + task);
			} catch (Exception e) {
				log.error(e, "Error " + task);
				throw new RuntimeException("Error " + task, e);
			}
			return null;
		}
		@Override
		public void process() {
			call();
		}
	}
	public void callBreakends(ExecutorService threadpool) {
		log.info("Identifying Breakpoints");
		try {
			if (processContext.shouldProcessPerChromosome()) {
				List<WriteMaximalCliquesForChromosome> workers = Lists.newArrayList();
				final SAMSequenceDictionary dict = processContext.getReference().getSequenceDictionary();
				for (int i = 0; i < dict.size(); i++) {					
					for (int j = i; j < dict.size(); j++) {
						workers.add(new WriteMaximalCliquesForChromosome(processContext, output, samEvidence, assemblyEvidence, i, j));
					}
				}
				if (threadpool != null) {
					log.info("Identifying Breakpoints in parallel");
					try {
						for (Future<Void> future : threadpool.invokeAll(workers)) {
							// throw exception from worker thread here
							future.get();
						}
					} catch (InterruptedException e) {
						log.error(e, "Interrupted Identifying Breakpoints");
						throw new RuntimeException(e);
					} catch (ExecutionException e) {
						log.error(e, "Error Identifying Breakpoints");
						throw new RuntimeException(e);
					}
				} else {
					log.info("Identifying Breakpoints sequentially");
					for (WriteMaximalCliquesForChromosome c : workers) {
						c.call();
					}
				}
			} else {
				File outfile = processContext.getFileSystemContext().getBreakpointVcf(output);
				if (outfile.exists()) {
					log.debug("Skipping breakpoint identification: output file exists");
				} else {
					CloseableIterator<DirectedEvidence> evidenceIt = null;
					File unsorted = FileSystemContext.getWorkingFileFor(outfile);
					File sorted = FileSystemContext.getWorkingFileFor(outfile, "sorted.");
					unsorted.delete();
					sorted.delete();
					try {
						boolean assemblyOnly = processContext.getVariantCallingParameters().callOnlyAssemblies;
						evidenceIt = getAllEvidence(true, true, !assemblyOnly, !assemblyOnly, !assemblyOnly);
						EvidenceClusterProcessor processor = new EvidenceClusterProcessor(processContext, evidenceIt);
						writeMaximalCliquesToVcf(
								processContext,
								processor,
								unsorted);
					} finally {
						CloserUtil.close(evidenceIt);
					}
					VcfFileUtil.sort(processContext, unsorted, sorted);
					FileHelper.move(sorted, outfile, true);
				}
			}
			log.info("Identifying Breakpoints complete");
		} catch (IOException e) {
			throw new RuntimeException(e);
		} finally {
			close();
		}
	}
	private List<Closeable> toClose = Lists.newArrayList();
	private CloseableIterator<IdsvVariantContext> getAllCalledVariants() {
		List<Iterator<IdsvVariantContext>> variants = Lists.newArrayList();
		if (processContext.shouldProcessPerChromosome()) {
			SAMSequenceDictionary dict = processContext.getReference().getSequenceDictionary();
			for (int i = 0; i < dict.size(); i++) {
				final String chri = dict.getSequence(i).getSequenceName();
				for (int j = i; j < dict.size(); j++) {
					final String chrj = dict.getSequence(j).getSequenceName();
					variants.add(getVariants(processContext.getFileSystemContext().getBreakpointVcf(output, chri, chrj)));
				}
			}
		} else {
			variants.add(getVariants(processContext.getFileSystemContext().getBreakpointVcf(output)));
		}
		CloseableIterator<IdsvVariantContext> merged = new AutoClosingMergedIterator<IdsvVariantContext>(variants, IdsvVariantContext.ByLocationStart);
		return new AsyncBufferedIterator<IdsvVariantContext>(merged, "all breakpoints");
	}
	public void close() {
		super.close();
		for (Closeable c : toClose) {
			if (c != null) {
				try {
					c.close();
				} catch (IOException e) {
					log.error(e, " error closing ", c);
				}
			}
		}
		toClose.clear();
	}
	private Iterator<IdsvVariantContext> getVariants(File file) {
		VCFFileReader vcfReader = new VCFFileReader(file, false);
		toClose.add(vcfReader);
		CloseableIterator<VariantContext> it = vcfReader.iterator();
		toClose.add(it);
		Iterator<IdsvVariantContext> idsvIt = Iterators.transform(it, new Function<VariantContext, IdsvVariantContext>() {
			@Override
			public IdsvVariantContext apply(VariantContext arg) {
				return IdsvVariantContext.create(processContext, null, arg);
			}
		});
		return idsvIt;
	}
	private static void writeMaximalCliquesToVcf(ProcessingContext processContext, Iterator<VariantContextDirectedEvidence> it, File vcf) {
		final ProgressLogger writeProgress = new ProgressLogger(log);
		log.info("Loading minimal evidence set for ", vcf, " into memory.");
		VariantContextWriter vcfWriter = null;
		try {
			vcfWriter = processContext.getVariantContextWriter(vcf, false);
			log.info("Start calling maximal cliques for ", vcf);
			while (it.hasNext()) {
				VariantContextDirectedEvidence loc = it.next();
				vcfWriter.add(loc);
				writeProgress.record(processContext.getDictionary().getSequence(loc.getBreakendSummary().referenceIndex).getSequenceName(), loc.getBreakendSummary().start);
			}
			log.info("Complete calling maximal cliques for ", vcf);
		} finally {
			if (vcfWriter != null) vcfWriter.close();
		}
	}
	public void annotateBreakpoints(File truthVcf) {
		log.info("Annotating Calls");
		List<SAMEvidenceSource> normal = Lists.newArrayList();
		List<SAMEvidenceSource> tumour = Lists.newArrayList();
		int maxFragmentSize = assemblyEvidence.getMaxConcordantFragmentSize();
		for (EvidenceSource source : samEvidence) {
			maxFragmentSize = Math.max(source.getMaxConcordantFragmentSize(), maxFragmentSize);
			SAMEvidenceSource samSource = (SAMEvidenceSource)source;
			if (samSource.isTumour()) {
				tumour.add(samSource);
			} else {
				normal.add(samSource);
			}
		}
		VariantContextWriter vcfWriter = null;
		ReferenceCoverageLookup normalCoverage = null;
		ReferenceCoverageLookup tumourCoverage = null;
		CloseableIterator<IdsvVariantContext> it = null;
		CloseableIterator<DirectedEvidence> evidenceIt = null;
		try {
			vcfWriter = processContext.getVariantContextWriter(FileSystemContext.getWorkingFileFor(output), true);
			it = getAllCalledVariants();
			Iterator<VariantContextDirectedEvidence> breakendIt = Iterators.filter(it, VariantContextDirectedEvidence.class);
			normalCoverage = getReferenceLookup(normal, 4 * maxFragmentSize);
			tumourCoverage = getReferenceLookup(tumour, 4 * maxFragmentSize);
			breakendIt = new SequentialCoverageAnnotator(processContext, breakendIt, normalCoverage, tumourCoverage);
			evidenceIt = getAllEvidence(true, true, true, true, true);
			breakendIt = new SequentialEvidenceAnnotator(processContext, breakendIt, evidenceIt, maxFragmentSize, true);
			if (truthVcf != null) {
				breakendIt = new TruthAnnotator(processContext, breakendIt, truthVcf);
			}
			while (breakendIt.hasNext()) {
				VariantContextDirectedEvidence variant = breakendIt.next();
				if (variant.isValid() && !variant.isFiltered() || Defaults.WRITE_FILTERED_CALLS) {
					vcfWriter.add(variant);
				}
			}
			CloserUtil.close(vcfWriter);
			FileHelper.move(FileSystemContext.getWorkingFileFor(output), output, true);
			log.info("Variant calls written to ", output);
		} catch (IOException e) {
			throw new RuntimeException(e);
		} finally {
			CloserUtil.close(vcfWriter);
			CloserUtil.close(normalCoverage);
			CloserUtil.close(tumourCoverage);
			CloserUtil.close(it);
			CloserUtil.close(evidenceIt);
		}
	}
	public ReferenceCoverageLookup getReferenceLookup(List<SAMEvidenceSource> input, int windowSize) {
		List<ReferenceCoverageLookup> lookup = Lists.newArrayList();
		for (SAMEvidenceSource s : input) {
			// one read-ahead thread per input file
			CloseableIterator<SAMRecord> it = new AsyncBufferedIterator<SAMRecord>(processContext.getSamReaderIterator(s.getSourceFile(), SortOrder.coordinate), s.getSourceFile().getName() + " reference coverage");
			toClose.add(it);
			lookup.add(new SequentialReferenceCoverageLookup(it, s.getReadPairConcordanceCalculator(), windowSize));
		}
		return new AggregateReferenceCoverageLookup(lookup);
	}
	private void writeOutput() {
		log.info("Outputting calls to ", output);
	}
}
