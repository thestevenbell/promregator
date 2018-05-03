package org.cloudfoundry.promregator.scanner;

import java.util.LinkedList;
import java.util.List;

import org.cloudfoundry.promregator.cfaccessor.CFAccessor;
import org.cloudfoundry.promregator.cfaccessor.CFAccessorMock;
import org.cloudfoundry.promregator.config.Target;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class MockedCachingTargetResolverSpringApplication {
	
	public static class MockedTargetResolver implements TargetResolver {
		public final static Target target1 = new Target();
		public final static Target target2 = new Target();
		public final static Target targetAllInSpace = new Target();
		
		public static ResolvedTarget rTarget1;
		public static ResolvedTarget rTarget2;
		
		private boolean requestForTarget1 = false;
		private boolean requestForTarget2 = false;
		private boolean requestForTargetAllInSpace = false;
		
		static {
			target1.setOrgName("unittestorg");
			target1.setSpaceName("unittestspace");
			target1.setApplicationName("testapp");
			target1.setPath("path");
			target1.setProtocol("https");
			
			target2.setOrgName("unittestorg");
			target2.setSpaceName("unittestspace");
			target2.setApplicationName("testapp2");
			target2.setPath("path");
			target2.setProtocol("https");
			
			targetAllInSpace.setOrgName("unittestorg");
			targetAllInSpace.setSpaceName("unittestspace");
			// application Name is missing
			targetAllInSpace.setPath("path");
			targetAllInSpace.setProtocol("https");
			
			rTarget1 = new ResolvedTarget(target1);
			rTarget2 = new ResolvedTarget(target2);
		}
		
		@Override
		public List<ResolvedTarget> resolveTargets(List<Target> configTarget) {
			List<ResolvedTarget> response = new LinkedList<>();
			
			for (Target t : configTarget) {
				if (t == target1) {
					this.requestForTarget1 = true;
					response.add(rTarget1);
				} else if (t == target2) {
					this.requestForTarget2 = true;
					response.add(rTarget2);
				} else if (t == targetAllInSpace) {
					this.requestForTargetAllInSpace = true;
					response.add(rTarget1);
					response.add(rTarget2);
				}
			}
			
			return response;
		}

		public boolean isRequestForTarget1() {
			return requestForTarget1;
		}

		public boolean isRequestForTarget2() {
			return requestForTarget2;
		}

		public boolean isRequestForTargetAllInSpace() {
			return requestForTargetAllInSpace;
		}
		
		public void resetRequestFlags() {
			this.requestForTarget1 = false;
			this.requestForTarget2 = false;
			this.requestForTargetAllInSpace = false;
		}
	}
	
	@Bean
	public CFAccessor cfAccessor() {
		return new CFAccessorMock();
	}
	
	@Bean
	public TargetResolver targetResolver() {
		return new MockedTargetResolver();
	}
	
	@Bean
	public CachingTargetResolver cachingTargetResolver(TargetResolver targetResolver) {
		return new CachingTargetResolver(targetResolver);
	}
	
	public static class RemovalHandler implements CachingTargetResolverRemovalListener {
		private boolean called = false;
		
		@Override
		public void onRemoval(Target removedConfigTarget, List<ResolvedTarget> removedResolvedTargets) {
			this.called = true;
		}

		public boolean isCalled() {
			return called;
		}
		
		public void reset() {
			this.called = false;
		}
	}
	
	@Bean
	public CachingTargetResolverRemovalListener testRemovalHandler() {
		return new RemovalHandler();
	}
	
}
